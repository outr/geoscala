package com.outr.geoscala

import java.io.File
import java.net.{HttpURLConnection, URL}
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.zip.{ZipEntry, ZipFile}

import com.outr.lucene4s._
import com.outr.lucene4s.facet.FacetField
import com.outr.lucene4s.field.Field
import com.outr.lucene4s.field.value.SpatialPoint
import com.outr.lucene4s.keyword.KeywordIndexing
import com.outr.lucene4s.mapper.Searchable
import com.outr.lucene4s.query.{Condition, QueryBuilder, SearchTerm}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.matching.Regex

class GeoScala(cacheDirectory: Path = Paths.get("cache"),
               indexDirectory: Path = Paths.get("index"),
               minimumUpdateAge: FiniteDuration = 30.days) {
  private val postalIndex = new Lucene(Some(indexDirectory.resolve("postal")), defaultFullTextSearchable = true)
  private val cityIndex = new Lucene(Some(indexDirectory.resolve("city")), defaultFullTextSearchable = true)

  val postalLocation: SearchablePostalLocation = postalIndex.create.searchable[SearchablePostalLocation]
  val location: SearchableLocation = cityIndex.create.searchable[SearchableLocation]

  val countryIndexing = new KeywordIndexing(postalIndex, "countryIndex", KeywordIndexing.FieldFromBuilder(location.countryCode))
  val nameIndexing = new KeywordIndexing(postalIndex, "nameIndex", KeywordIndexing.FieldFromBuilder(location.name), List(location.countryCode))
  val stateIndexing = new KeywordIndexing(postalIndex, "stateIndex", KeywordIndexing.FieldFromBuilder(location.stateName), List(location.countryCode))
  val provinceIndexing = new KeywordIndexing(postalIndex, "provinceIndex", KeywordIndexing.FieldFromBuilder(location.provinceName), List(location.countryCode))
  val communityIndexing = new KeywordIndexing(postalIndex, "communityIndex", KeywordIndexing.FieldFromBuilder(location.communityName), List(location.countryCode))

  private val updating = new AtomicReference[Future[Unit]](updateCache())
  private var ready: Boolean = false

  def future: Future[Unit] = updating.get()
  def isReady: Boolean = ready

  /**
    * Updates the GeoNames file and updates the index with the data
    */
  def requestUpdate(force: Boolean = false): Boolean = synchronized {
    if (updating.get().isCompleted) {
      updateCache(force)
      true
    } else {
      false
    }
  }

  object search {
    def postal(code: String, countryCode: Option[String] = None): QueryBuilder[PostalLocation] = {
      val searchTerm = countryCode match {
        case Some(cc) => grouped(
          exact(postalLocation.countryCode(cc)) -> Condition.Must,
          exact(postalLocation.postalCode(code)) -> Condition.Must
        )
        case None => exact(postalLocation.postalCode(code))
      }
      postalLocation
        .query()
        .filter(searchTerm)
    }
    def address(text: String, countryCode: Option[String] = None): QueryBuilder[Location] = {
      val queryText: String = text.split(Array(' ', ',', '.')).collect {
        case s if s.trim.nonEmpty => s"+($s*^4 OR $s~0.8)"
      }.mkString(" ")
      val searchTerm = countryCode match {
        case Some(cc) => grouped(
          exact(location.countryCode(cc)) -> Condition.Must,
          parse(queryText) -> Condition.Must
        )
        case None => parse(queryText)
      }
      location
        .query()
        .filter(searchTerm)
    }
  }

  private def updateCache(force: Boolean = false): Future[Unit] = {
    val f = Future {
      // Create cache directory if it doesn't exist
      if (!Files.isDirectory(cacheDirectory)) {
        Files.createDirectories(cacheDirectory)
      }

      // Determine currently cached date
      val cacheZIP: Path = cacheDirectory.resolve("allCountries.zip")
      val cacheLastModified = if (Files.exists(cacheZIP)) Files.getLastModifiedTime(cacheZIP).toMillis else 0L

      // Determine if there is a newer file available
      val geoNamesLastModified = {
        val connection = GeoScala.AllCountriesURL.openConnection().asInstanceOf[HttpURLConnection]
        try {
          connection.getLastModified
        } finally {
          connection.disconnect()
        }
      }

      // Download latest GeoNames file if newer exists
      val expiredCache = cacheLastModified + minimumUpdateAge.toMillis < geoNamesLastModified
      if (expiredCache || force) {
        scribe.info(s"Modified Since Last Updated. GeoNames: $geoNamesLastModified, Cached: $cacheLastModified, Minimum Update Age: $minimumUpdateAge.")
        val input = GeoScala.AllCountriesURL.openStream()
        try {
          Files.copy(input, cacheZIP, StandardCopyOption.REPLACE_EXISTING)
          Files.setLastModifiedTime(cacheZIP, FileTime.fromMillis(geoNamesLastModified))
        } finally {
          input.close()
        }

        // Extract GeoNames ZIP
        scribe.info(s"Extracting $cacheZIP...")
        UberZip.unzip(cacheZIP.toFile, cacheDirectory.toFile, 8)

        // Clear index
        scribe.info("Removing all records before importing...")
        postalIndex.deleteAll()
        cityIndex.deleteAll()

        // Import and update index
        scribe.info("Importing lines from allCountries.txt...")
        val cacheText = cacheDirectory.resolve("allCountries.txt")
        val lineCount = {
          val stream = Files.lines(cacheText)
          try {
            stream.count()
          } finally {
            stream.close()
          }
        }
        val lines = Files.lines(cacheText)
        var lastLog = System.currentTimeMillis()
        lines.parallel().iterator().asScala.zipWithIndex.foreach {
          case (line@GeoScala.LineRegex(cc, pc, pn, an1, ac1, an2, ac2, an3, ac3, lat, lon, acc), index) => try {
            if (lat.isEmpty || lon.isEmpty) {
              scribe.warn(s"Latitude or Longitude is blank for: cc: $cc, pc: $pc, pn: $pn, an1: $an1, an2: $an2, an3: $an3, lat: $lat, lon: $lon, acc: $acc (line: $line). Skipping!")
            } else {
              val l = SpatialPoint(lat.toDouble, lon.toDouble)
              val locationPath = List(cc, an3, an2, an1, pn).filterNot(_.isEmpty)

              val time = System.currentTimeMillis()
              if (lastLog + 5000L < time) {
                scribe.info(s"Processing $index of $lineCount")
                lastLog = time
              }

              // Postal index
              val pl = PostalLocation(
                countryCode = cc,
                postalCode = pc,
                name = pn,
                stateName = an1,
                stateCode = ac1,
                provinceName = an2,
                provinceCode = ac2,
                communityName = an3,
                communityCode = ac3,
                point = l,
                accuracy = if (acc.nonEmpty) acc.toInt else 0
              )
              postalLocation
                .insert(pl)
                .facets(postalLocation.locationFacet(locationPath: _*))
                .index()

              // Name index
              val cl = Location(
                countryCode = cc,
                name = pn,
                stateName = an1,
                stateCode = ac1,
                provinceName = an2,
                provinceCode = ac2,
                communityName = an3,
                communityCode = ac3,
                point = l,
                accuracy = if (acc.nonEmpty) acc.toInt else 0
              )
              location
                .update(cl)
                .facets(location.locationFacet(locationPath: _*))
                .index()
            }
          } catch {
            case t: Throwable => {
              Files.delete(cacheZIP)
              throw new RuntimeException(s"Failed to process line: [$line] (cc: $cc, pc: $pc, pn: $pn, an1: $an1, an2: $an2, an3: $an3, lat: $lat, lon: $lon, acc: $acc)", t)
            }
          }
        }
        lines.close()

        // Commit the index changes
        scribe.info("Committing changes...")
        postalIndex.commit()
        cityIndex.commit()

        // Delete the TXT file
        scribe.info("Deleting allCountries.txt...")
        Files.delete(cacheText)

        scribe.info("Update complete")
      }
    }

    f.foreach { u =>
      ready = true
    }
    f
  }

  trait SearchableBase {
    def countryCode: Field[String]
    def name: Field[String]
    def stateName: Field[String]
    def stateCode: Field[String]
    def provinceName: Field[String]
    def provinceCode: Field[String]
    def communityName: Field[String]
    def communityCode: Field[String]
    def point: Field[SpatialPoint]
    def accuracy: Field[Int]
  }

  trait SearchablePostalLocation extends Searchable[PostalLocation] with SearchableBase {
    override def idSearchTerms(t: PostalLocation): List[SearchTerm] = List(
      exact(countryCode(t.countryCode)),
      exact(postalCode(t.postalCode))
    )

    def postalCode: Field[String]

    val locationFacet: FacetField = postalIndex.create.facet("locationFacet", hierarchical = true)
  }

  trait SearchableLocation extends Searchable[Location] with SearchableBase {
    override def idSearchTerms(t: Location): List[SearchTerm] = List(
      exact(countryCode(t.countryCode)),
      exact(name(t.name)),
      exact(stateName(t.stateName)),
      exact(provinceName(t.provinceName))
      //exact(communityName(t.communityName))     // TODO: could this be because communityName is blank?
    )

    val locationFacet: FacetField = cityIndex.create.facet("locationFacet", hierarchical = true)
  }
}

object GeoScala {
  val AllCountriesURL = new URL("http://download.geonames.org/export/zip/allCountries.zip")
  val LineRegex: Regex = """(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)""".r

  def main(args: Array[String]): Unit = {
    val gs = new GeoScala()
    Await.result(gs.future, Duration.Inf)
    scribe.info("Finished!")
  }
}

case class PostalLocation(countryCode: String,
                          postalCode: String,
                          name: String,
                          stateName: String,
                          stateCode: String,
                          provinceName: String,
                          provinceCode: String,
                          communityName: String,
                          communityCode: String,
                          point: SpatialPoint,
                          accuracy: Int)

case class Location(countryCode: String,
                    name: String,
                    stateName: String,
                    stateCode: String,
                    provinceName: String,
                    provinceCode: String,
                    communityName: String,
                    communityCode: String,
                    point: SpatialPoint,
                    accuracy: Int)

object UberZip {
  def unzip(file: File, directory: File, threadCount: Int): Unit = {
    directory.mkdirs()

    val executor = Executors.newFixedThreadPool(threadCount)

    val counter = new AtomicInteger()

    class UnzipWorker(zip: ZipFile, entry: ZipEntry, directory: File) extends Runnable {
      override def run(): Unit = {
        try {
          UberZip.unzip(zip, entry, directory)
        } catch {
          case t: Throwable => scribe.error(t)
        }
        counter.decrementAndGet()
      }
    }

    val zip = new ZipFile(file)
    zip.entries().asScala.foreach { entry =>
      if (entry.getName.endsWith("/")) {
        // Create directory
        val dir = new File(directory, entry.getName)
        dir.mkdirs()
      } else {
        counter.incrementAndGet()
        executor.submit(new UnzipWorker(zip, entry, directory))
      }
    }

    while (counter.get() > 0) {
      Thread.sleep(10)
    }
    executor.shutdown()
  }

  def unzip(zip: ZipFile, entry: ZipEntry, directory: File): Unit = {
    val output = directory.toPath.toAbsolutePath.resolve(entry.getName)
    val input = zip.getInputStream(entry)
    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
  }
}