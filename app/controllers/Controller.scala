package controllers

import akka.actor.ActorSystem
import dao._
import io.circe.{Encoder, Json}
import io.circe.parser.{parse => circeParse}
import io.circe.syntax._
import javax.inject._
import models._
import org.ergoplatform.wallet.serialization.JsonCodecsWrapper
import org.ergoplatform.ErgoBox
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc._
import utils.ErrorHandler._
import utils.NodeProcess.lastHeight

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Singleton
class Controller @Inject()(extractedBlockDAO: ExtractedBlockDAO, scanDAO: ScanDAO, outputDAO: OutputDAO, cc: ControllerComponents, actorSystem: ActorSystem)(implicit exec: ExecutionContext) extends AbstractController(cc){

  private val logger: Logger = Logger(this.getClass)

  /**
   * Sample controller
   */
  def home: Action[AnyContent] = Action { implicit request =>
  logger.info("First Api!")
    Ok("ok")
  }

  /**
   * Register Scan. Route: /scan/register
   */
  def scanRegister: Action[JsValue] = Action(parse.json) { implicit request =>
    try {
      var result: Json = Json.Null
      val scanJson = circeParse(request.body.toString).toOption.get
      Scan.scanDecoder.decodeJson(scanJson).toTry match {
        case Success(scan) =>
          val addedScan = scanDAO.create(Scan(scan))
          result = Json.fromFields(List(
          ("scanId", Json.fromInt(addedScan.scanId))
          ))
        case Failure(e) => throw new Exception(e)
      }
       Ok(result.toString()).as("application/json")
    }
    catch {
      case e: Exception => errorResponse(e)
    }
  }

  /**
   * Deregister Scan. Route: /scan/deregister
   */
  def scanDeregister: Action[JsValue] = Action(parse.json) { implicit request =>
    try {
      var result: Json = Json.Null
      circeParse(request.body.toString).toTry match {
        case Success(scanIdJs) =>
          val id = scanIdJs.hcursor.downField("scanId").as[Int].getOrElse(throw new Exception("scanId is required"))
          val numberDeleted = scanDAO.deleteById(id)
          if (numberDeleted > 0) result = Json.fromFields(List(("scanId", Json.fromInt(id))))
          else throw NotFoundException("scanId not found")
        case Failure(e) => throw new Exception(e)
      }
       Ok(result.toString()).as("application/json")
    }
    catch {
      case m: NotFoundException => notFoundResponse(m.getMessage)
      case e: Exception => errorResponse(e)
    }
  }

  /**
   * list all scans. Route: /scan/listAll
   */
  def listAllScans: Action[AnyContent] = Action { implicit request =>
    try {
      val scans = scanDAO.selectAll.scans.map(Scan.scanEncoder.apply)
       Ok(scans.asJson.toString()).as("application/json")
    }
    catch {
      case e: Exception => errorResponse(e)
    }
  }

  /**
   * List boxes which are unSpent for spec scanId. Route: /scan/unspentBoxes/{scanId}
   */
  def listUBoxes(scanId: Int, minConfirmations: Int, minInclusionHeight: Int): Action[AnyContent] = Action { implicit request =>
    try {
      val scans = outputDAO.selectUnspentBoxesWithScanId(scanId, lastHeight - minConfirmations, minInclusionHeight)
      implicit val boxDecoder: Encoder[ErgoBox] = JsonCodecsWrapper.ergoBoxEncoder
      Ok(scans.asJson.toString()).as("application/json")
    }
    catch {
      case e: Exception => errorResponse(e)
    }
  }

  /**
   * status of scanner. Route: /info
   *
   * @return {
   *            "lastScannedHeight": 563885,
   *            "networkHeight": 573719
   *         }
   */
  def scanInfo: Action[AnyContent] = Action { implicit request =>
    try {
      val lastScannedHeight = ("lastScannedHeight", Json.fromInt(extractedBlockDAO.getLastHeight))
      val networkHeight = ("networkHeight", Json.fromInt(lastHeight))
      Ok(Json.fromFields(List(lastScannedHeight, networkHeight)).toString()).as("application/json")
    }
    catch {
      case e: Exception => errorResponse(e)
    }
  }

}
