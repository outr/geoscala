package com.outr.geoscala

import java.io.{File, FileOutputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.{ZipEntry, ZipFile}

import com.outr.lucene4s._
import com.outr.lucene4s.facet.FacetField
import com.outr.lucene4s.field.Field
import com.outr.lucene4s.field.value.SpatialPoint
import com.outr.lucene4s.query.Condition
import com.outr.scribe.Logging

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.util.matching.Regex

class GeoScala(cacheDirectory: Path, indexDirectory: Option[Path] = None) extends Logging {
  private val lucene = new Lucene(indexDirectory, defaultFullTextSearchable = true)

  val countryCode: Field[String] = lucene.create.field[String]("countryCode")
  val postalCode: Field[String] = lucene.create.field[String]("postCode")
  val name: Field[String] = lucene.create.field[String]("name")
  val stateName: Field[String] = lucene.create.field[String]("stateName")
  val stateCode: Field[String] = lucene.create.field[String]("stateCode")
  val provinceName: Field[String] = lucene.create.field[String]("provinceName")
  val provinceCode: Field[String] = lucene.create.field[String]("provinceCode")
  val communityName: Field[String] = lucene.create.field[String]("communityName")
  val communityCode: Field[String] = lucene.create.field[String]("communityCode")
  val location: Field[SpatialPoint] = lucene.create.field[SpatialPoint]("location")
  val accuracy: Field[Int] = lucene.create.field[Int]("accuracy")
  val locationFacet: FacetField = lucene.create.facet("locationFacet", hierarchical = true)


//  def isReady: Boolean
//  def search(text: String, limit: Int = 10): List[Location]

  /**
    * Updates the GeoNames file and updates the index with the data
    */
  def updateCache(): Future[Unit] = Future {
    // Determine currently cached date
    val cacheZIP = cacheDirectory.resolve("allCountries.zip")
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
    if (geoNamesLastModified > cacheLastModified) {
      logger.info(s"Modified Since Last Updated. GeoNames: $geoNamesLastModified, Cached: $cacheLastModified.")
      val input = GeoScala.AllCountriesURL.openStream()
      try {
        Files.copy(input, cacheZIP)
      } finally {
        input.close()
      }
    }

    // Extract GeoNames ZIP
    logger.info(s"Extracting $cacheZIP...")
    UberZip.unzip(cacheZIP.toFile, cacheDirectory.toFile, 8)

    // Import and update index
    val cacheText = cacheDirectory.resolve("allCountries.txt")
    val lines = Files.lines(cacheText)
    lines.iterator().asScala.foreach {
      case line@GeoScala.LineRegex(cc, pc, pn, an1, ac1, an2, ac2, an3, ac3, lat, lon, acc) => try {
        logger.debug(s"cc: $cc, pc: $pc, pn: $pn, an1: $an1, an2: $an2, an3: $an3, lat: $lat, lon: $lon, acc: $acc")
        val l = SpatialPoint(lat.toDouble, lon.toDouble)
        val locationPath = List(cc, an3, an2, an1, pn).filterNot(_.isEmpty)
        lucene
          .update(grouped(exact(countryCode(cc)) -> Condition.Must, exact(postalCode(pc)) -> Condition.Must))
          .fields(
            countryCode(cc), postalCode(pc), name(pn), stateName(an1), stateCode(ac1), provinceName(an2),
            provinceCode(ac2), communityName(an3), communityCode(ac3), location(l), accuracy(if (acc.nonEmpty) acc.toInt else 0)
          )
          .facets(locationFacet(locationPath: _*))
          .index()
      } catch {
        case t: Throwable => throw new RuntimeException(s"Failed to process line: $line! (lat: $lat, lon: $lon)", t)
      }
    }
    lines.close()
  }
}

object GeoScala extends Logging {
  val AllCountriesURL = new URL("http://download.geonames.org/export/zip/allCountries.zip")
  val LineRegex: Regex = """(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)\t(.*)""".r

  def main(args: Array[String]): Unit = {
    val gs = new GeoScala(Paths.get("cache"))
    val future = gs.updateCache()
    Await.result(future, Duration.Inf)
    logger.info("Finished!")
  }
}

case class Location(countryCode: String,
                    postalCode: String,
                    name: String,
                    state: LocationPart,
                    province: LocationPart,
                    community: LocationPart,
                    point: SpatialPoint,
                    accuracy: Int)

case class LocationPart(name: String, code: String)

object UberZip extends Logging {
  def unzip(file: File, directory: File, threadCount: Int): Unit = {
    directory.mkdirs()

    val executor = Executors.newFixedThreadPool(threadCount)

    val counter = new AtomicInteger()

    class UnzipWorker(zip: ZipFile, entry: ZipEntry, directory: File) extends Runnable {
      override def run(): Unit = {
        try {
          UberZip.unzip(zip, entry, directory)
        } catch {
          case t: Throwable => logger.error(t)
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