package de.dnpm.cdn.etl.util

import scala.concurrent.duration._
import scala.util.chaining._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{
  Materializer,
  SystemMaterializer
}
import play.api.libs.ws.{
  StandaloneWSRequest => WSRequest
}
import com.typesafe.sslconfig.ssl.{
  KeyManagerConfig,
  KeyStoreConfig,
  TrustManagerConfig,
  TrustStoreConfig,
  SSLConfigSettings,
  SSLLooseConfig
}
import play.api.libs.ws.WSClientConfig
import play.api.libs.ws.ahc.{
  AhcWSClientConfig,
  StandaloneAhcWSClient
}
import de.dnpm.dip.coding.Code
import de.dnpm.dip.model.Site


object DIPNodeConnector
{

  final case class Config
  (
    baseUrl: String,
    timeout: Option[Int] = None,
    keystore: Option[(String,Option[String])] = None,
    truststore: Option[(String,Option[String])] = None,
    viaBroker: Boolean = true,
  )
  {
    val timeoutSecs = timeout.getOrElse(30) seconds
  }

  object Config
  {
    import Args._

    def fromArgs(args: Array[String]): Config =
      Config(
        args.value("baseUrl").get,  // Let it crash if missing
        args.value("timeout").map(_.toInt),
        args.value("keystore").map(_ split "\\|").map(psv => (psv(0),psv.lift(1))),
        args.value("truststore").map(_ split "\\|").map(psv => (psv(0),psv.lift(1))),
        args.value("broker").map(_.toBoolean).getOrElse(true)
      )
    
  } 

  private lazy implicit val system: ActorSystem = ActorSystem()

  private lazy implicit val materializer: Materializer = SystemMaterializer(system).materializer

}


abstract class DIPNodeConnector
(
  config: DIPNodeConnector.Config
)
{
  import DIPNodeConnector._

  protected val wsclient = {

    val keyManagerConfig =
      config.keystore.map { case (file,password) =>
        val keyStoreConfig =
          KeyStoreConfig(data = None, filePath = Some(file))
            .withPassword(password)
            .withStoreType("PKCS12")

        KeyManagerConfig().withKeyStoreConfigs(List(keyStoreConfig))
      }

    val trustManagerConfig =
      config.truststore.map { case (file,password) =>
        val trustStoreConfig =
          TrustStoreConfig(data = None, filePath = Some(file))
            .withPassword(password)
            .withStoreType("PKCS12")

        TrustManagerConfig().withTrustStoreConfigs(List(trustStoreConfig))
      }

    val sslConfig =
      SSLConfigSettings()
        .withProtocol("TLSv1.3")                       
        .withEnabledProtocols(Some(List("TLSv1.3","TLSv1.2")))
        .withLoose(
          SSLLooseConfig()
            .withAcceptAnyCertificate(true)
            .withDisableHostnameVerification(true)
        )
        .pipe(ssl =>
          (keyManagerConfig,trustManagerConfig) match {
            case (Some(keyManager),Some(trustManager)) => ssl.withKeyManagerConfig(keyManager).withTrustManagerConfig(trustManager)
       
            case (Some(keyManager),None) => ssl.withKeyManagerConfig(keyManager)
       
            case (None,Some(trustManager)) => ssl.withTrustManagerConfig(trustManager)
       
            case (None,None) => ssl
          }
        )

    StandaloneAhcWSClient(
      AhcWSClientConfig(wsClientConfig = WSClientConfig(ssl = sslConfig))
    )

  }

  protected def request(
    site: Code[Site],
    rawUri: String
  ): WSRequest = {
    val uri =
      if (rawUri.startsWith("/")) rawUri.substring(1)
      else rawUri

    if (config.viaBroker)
      wsclient.url(s"${config.baseUrl}/api/$uri")
        .withVirtualHost(s"${site.value.toLowerCase.replace("é","e")}.dnpm.de")
        .withRequestTimeout(config.timeoutSecs)
    else
      wsclient.url(s"${config.baseUrl}/$uri")
        .withRequestTimeout(config.timeoutSecs)
  }

}
