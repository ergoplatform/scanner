package services

import akka.actor.{ActorRef, ActorSystem, Props}
import javax.inject._
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import settings.Configuration

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StartupService @Inject()(appLifecycle: ApplicationLifecycle,
                               system: ActorSystem, scannerTask: ScannerTask, initBestBlockTask: InitBestBlockTask)
                              (implicit ec: ExecutionContext) {

  private val logger: Logger = Logger(this.getClass)

  logger.info("Scanner started!")


  val jobs: ActorRef = system.actorOf(Props(new Jobs(scannerTask, initBestBlockTask)), "scheduler")

  system.scheduler.scheduleAtFixedRate(
    initialDelay = 10.seconds,
    interval = 60.seconds,
    receiver = jobs,
    message = JobsInfo.blockChainScan
  )

  system.scheduler.scheduleOnce(
    delay = 0.seconds,
    receiver = jobs,
    message = JobsInfo.InitBestBlockInDb
  )

  appLifecycle.addStopHook { () =>
    logger.info("Scanner stopped")
    Configuration.stopScanning()
    system.stop(jobs)
    Future.successful(())
  }
}
