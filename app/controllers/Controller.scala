package controllers

import akka.actor.ActorSystem
import javax.inject._
import play.api.Logger
import play.api.mvc._

import scala.concurrent.ExecutionContext

@Singleton
class Controller @Inject()(cc: ControllerComponents, actorSystem: ActorSystem)(implicit exec: ExecutionContext) extends AbstractController(cc) {

  private val logger: Logger = Logger(this.getClass)

  /**
   * Sample controller
   */
  def home: Action[AnyContent] = Action { implicit request =>
  logger.info("First Api!")
    Ok("ok")
  }

}
