package com.softwaremill.sql

import com.softwaremill.sql.TrackType.TrackType
import io.getquill.{PostgresAsyncContext, SnakeCase}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.{ global => ec } // making tx-scoped ECs work
import scala.concurrent.duration._

object QuillTests extends App with DbSetup {
  dbSetup()

  lazy val ctx = new PostgresAsyncContext[SnakeCase]("ctx")

  import ctx._

  implicit val encodeTrackType = MappedEncoding[TrackType, Int](_.id)
  implicit val decodeTrackType = MappedEncoding[Int, TrackType](TrackType.byIdOrThrow)

  // note: we can use pure case classes, except for embedded values, which need to extend Embedded

  def insertWithGeneratedId(): Future[Unit] = {
    val q = quote {
      query[City].insert(lift(City(CityId(0), "New York", 19795791, 141300, None))).returning(_.id)
    }

    ctx.run(q).map { r =>
      println(s"Inserted, generated id: $r")
      println()
    }
  }

  def selectAll(): Future[Unit] = {
    val q = quote {
      query[City]
    }

    logResults("All cities", ctx.run(q))
  }

  def selectAllLines(): Future[Unit] = {
    // not necessary here, but just to demonstrate how to map to non-conventional db names
    val metroLines = quote {
      querySchema[MetroLine](
        "metro_line",
        _.id -> "id", // not all columns have to be specified
        _.stationCount -> "station_count"
      )
    }

    val q = quote {
      metroLines
    }

    logResults("All lines", ctx.run(q))
  }

  def selectNamesOfBig(): Future[Unit] = {
    val bigLimit = 4000000

    val q = quote {
      query[City].filter(_.population > lift(bigLimit)).map(_.name)
    }

    logResults("All city names with population over 4M", ctx.run(q))
  }

  def selectMetroSystemsWithCityNames(): Future[Unit] = {
    case class MetroSystemWithCity(metroSystemName: String, cityName: String, dailyRidership: Int)

    val q = quote {
      for {
        ms <- query[MetroSystem]
        c <- query[City] if c.id == ms.cityId
      } yield (ms.name, c.name, ms.dailyRidership)
    }

    val result = ctx.run(q).map(_.map(MetroSystemWithCity.tupled))

    logResults("Metro systems with city names", result)
  }

  def selectMetroLinesSortedByStations(): Future[Unit] = {
    case class MetroLineWithSystemCityNames(
      metroLineName: String, metroSystemName: String, cityName: String, stationCount: Int)

    // other joins (using for comprehensions cause compile errors)
    val q = quote {
      (for {
        ((ml, ms), c) <- query[MetroLine]
          .join(query[MetroSystem]).on(_.systemId == _.id)
          .join(query[City]).on(_._2.cityId == _.id)
      } yield (ml.name, ms.name, c.name, ml.stationCount)).sortBy(_._4)(Ord.desc)
    }

    val result = ctx.run(q).map(_.map(MetroLineWithSystemCityNames.tupled))

    logResults("Metro lines sorted by station count", result)
  }

  /*
  Doesn't compile.
  def selectMetroSystemsWithMostLines(): Future[Unit] = {
    case class MetroSystemWithLineCount(metroSystemName: String, cityName: String, lineCount: Long)

    val q = quote {
      (for {
        ((ml, ms), c) <- query[MetroLine]
          .join(query[MetroSystem]).on(_.systemId == _.id)
          .join(query[City]).on(_._2.cityId == _.id)
      } yield (ml, ms, c))
        .groupBy { case (ml, ms, c) => (ms.id, c.id, ms.name, c.name) }
        .map { case ((msId, cId, msName, cName), aggregated) => (msName, cName, aggregated.size) }
    }

    val result = ctx.run(q).map(_.map(MetroSystemWithLineCount.tupled))

    logResults("Metro systems with most lines", result)
  }
  */

  def selectCitiesWithSystemsAndLines(): Future[Unit] = {
    case class CityWithSystems(id: CityId, name: String, population: Int, area: Float, link: Option[String], systems: Seq[MetroSystemWithLines])
    case class MetroSystemWithLines(id: MetroSystemId, name: String, dailyRidership: Int, lines: Seq[MetroLine])

    val q = quote {
      for {
        ((ml, ms), c) <- query[MetroLine]
          .join(query[MetroSystem]).on(_.systemId == _.id)
          .join(query[City]).on(_._2.cityId == _.id)
      } yield (ml, ms, c)
    }

    val result = ctx.run(q).map(_
      .groupBy(_._3)
      .map { case (c, linesSystemsCities) =>
        val systems = linesSystemsCities.groupBy(_._2)
          .map { case (s, linesSystems) =>
            MetroSystemWithLines(s.id, s.name, s.dailyRidership, linesSystems.map(_._1))
          }
        CityWithSystems(c.id, c.name, c.population, c.area, c.link, systems.toSeq)
      })
      .map(_.toSeq)

    logResults("Cities with list of systems with list of lines", result)
  }

  def selectLinesConstrainedDynamically(): Future[Unit] = {
    val minStations: Option[Int] = Some(10)
    val maxStations: Option[Int] = None
    val sortDesc: Boolean = true

    val allFilter = quote {
      (ml: MetroLine) => true
    }
    val minFilter = minStations.map(limit => quote {
      (ml: MetroLine) => ml.stationCount >= lift(limit)
    }).getOrElse(allFilter)

    val maxFilter = maxStations.map(limit => quote {
      (ml: MetroLine) => ml.stationCount <= lift(limit)
    }).getOrElse(allFilter)

    val sortOrder = if (sortDesc) quote { Ord.desc[Int] } else quote { Ord.asc[Int] }

    val q = quote {
      query[MetroLine]
        .filter(ml => minFilter(ml) && maxFilter(ml))
        .sortBy(_.stationCount)(sortOrder)
    }

    logResults("Lines constrained dynamically", ctx.run(q))
  }

  def plainSql(): Future[Unit] = {
    case class MetroSystemWithCity(metroSystemName: String, cityName: String, dailyRidership: Int)
    
    val q = quote {
      infix"""SELECT ms.name as metro_system_name, c.name as city_name, ms.daily_ridership as daily_ridership
        FROM metro_system as ms
        JOIN city AS c ON ms.city_id = c.id
        ORDER BY ms.daily_ridership DESC""".as[Query[MetroSystemWithCity]]
    }

    val result = ctx.run(q)

    logResults("Plain sql", result)
  }

  def transactions(): Future[Unit] = {
    def insert(c: City)(implicit ec: ExecutionContext): Future[City] = ctx.run {
      query[City].insert(lift(c)).returning(_.id)
    }.map(id => c.copy(id = id))

    def delete(id: CityId)(implicit ec: ExecutionContext): Future[Long] = ctx.run {
      query[City].filter(_.id == lift(id)).delete
    }

    println("Transactions")
    ctx.transaction { implicit ec =>
      for {
        inserted <- insert(City(CityId(0), "Invalid", 0, 0, None))
        deleted <- delete(inserted.id)
      } yield {
        println(s"Deleted $deleted rows")
        println()
      }
    }
  }

  private def logResults[R](label: String, f: Future[Seq[R]]): Future[Unit] = {
    f.map { r =>
      println(label)
      r.foreach(println)
      println()
    }
  }

  val tests = for {
    _ <- insertWithGeneratedId()
    _ <- selectAll()
    _ <- selectAllLines()
    _ <- selectNamesOfBig()
    _ <- selectMetroSystemsWithCityNames()
    _ <- selectMetroLinesSortedByStations()
    //_ <- selectMetroSystemsWithMostLines()
    _ <- selectCitiesWithSystemsAndLines()
    _ <- selectLinesConstrainedDynamically()
    _ <- plainSql()
    _ <- transactions()
  } yield ()

  try Await.result(tests, 1.minute)
  finally ctx.close()
}
