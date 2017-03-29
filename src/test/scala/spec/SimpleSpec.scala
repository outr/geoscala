package spec

import com.outr.geoscala.GeoScala
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SimpleSpec extends WordSpec with Matchers {
  "GeoScala" should {
    val geoScala = new GeoScala()

    "startup successfully" in {
      Await.result(geoScala.future, Duration.Inf)
    }
    "validate the proper database size" in {
      geoScala.postalLocation.query().limit(1).search().total should be(1236266)
      geoScala.location.query().limit(1).search().total should be(892730)
    }
    "find one result for a ZIP code" in {
      val page = geoScala.search.postal("73072", countryCode = Some("US")).search()
      page.total should be(1)
      val location = page.entries.head
      location.name should be("Norman")
      location.stateName should be("Oklahoma")
      location.provinceName should be("Cleveland")
      location.communityName should be("")
    }
    "find one result for an address search" in {
      val page = geoScala.search.address("Norman, Oklahoma", Some("US")).search()
      page.total should be(1)
      val location = page.entries.head
      location.name should be("Norman")
      location.stateName should be("Oklahoma")
      location.provinceName should be("Cleveland")
      location.communityName should be("")
    }
  }
}
