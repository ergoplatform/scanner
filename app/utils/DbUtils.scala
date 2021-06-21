package utils

import play.api.db.slick.HasDatabaseConfigProvider
import slick.jdbc.JdbcProfile

import scala.concurrent.Future
import scala.util.Try

trait DbUtils { self: HasDatabaseConfigProvider[JdbcProfile] =>
  import profile.api._

  /**
  * exec a dbio query as transactionally
   * @param dbio DBIO[T]
   * @tparam T Any
   * @return
   */
  def execTransact[T](dbio: DBIO[T]): Future[T] =
    db.run(dbio.transactionally)

  /**
  * exception handling for get any object from db
   * @param inp
   * @tparam T
   * @return
   */
  def notFoundHandle[T](inp: Try[Option[T]]): T = {
    inp.toEither match {
      case Right(Some(result)) => result
      case Right(None) =>  throw new Exception("object not Found")
      case Left(ex) => throw ex
    }
  }

}
