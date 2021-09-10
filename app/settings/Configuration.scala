package settings
import pureconfig.ConfigSource
import pureconfig.generic.auto._

object Configuration {
  val serviceConf: ScannerConf = ConfigSource.default.load[ScannerConf] match {
    case Right(conf) => conf
    case Left(error) => throw new Exception(error.toString())
  }

  private var activeScanning: Boolean = false

  def startScanning(): Unit = {
    activeScanning = true
  }

  def stopScanning(): Unit = {
    activeScanning = false
  }

  def isActiveScanning: Boolean = {
    activeScanning
  }

}
