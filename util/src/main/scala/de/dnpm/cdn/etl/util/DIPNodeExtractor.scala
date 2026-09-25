package de.dnpm.cdn.etl.util


import java.io.{
  File,
  FileInputStream,
  FileWriter
}
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
import scala.concurrent.{
  ExecutionContext,
  Future
}
import scala.util.{
  Success,
  Try,
  Using
}
import scala.util.chaining._
import cats.data.{
  EitherNel,
  NonEmptyList,
}
import cats.syntax.either._
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Code
import de.dnpm.dip.model.{
  Id,
  Site
}
import de.dnpm.dip.service.mvh.{
  Submission,
  TransferTAN,
  UseCase
}
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.json.{
  Json,
  JsValue,
  Format,
  Reads,
  Writes
}
import de.dnpm.cdn.etl.util.{ 
  Collection,
  DIPNodeConnector
}


object DIPNodeExtractor
{

  import Args._

  def apply(args: Array[String]): DIPNodeExtractor =
    new DIPNodeExtractor(
      DIPNodeConnector.Config.fromArgs(args),
      new File(args.value("srcDir").getOrElse("./submissions"))
    )

}


class DIPNodeExtractor
(
  config: DIPNodeConnector.Config,
  dir: File
)
extends DIPNodeConnector(config)
with Extractor[Future,ExecutionContext]
with Logging
{

  dir.mkdirs

  private def submissionReportsFile(
    site: Code[Site],
    useCase: UseCase.Value
  ): File =
    new File(dir,s"${site}_${useCase}_SubmissionReports.json")

  private def submissionFile(
    tan: Id[TransferTAN],
    site: Code[Site],
    useCase: UseCase.Value
  ): File =
    new File(dir,s"${site}_${useCase}_Submission_${tan}.json")

  private def fromJsonFile[T: Reads](
    file: File
  ): EitherNel[String,T] =
    Using(new FileInputStream(file))(Json.parse(_))
      .map(Json.fromJson[T](_))
      .get
      .asEither
      .leftMap(errs => NonEmptyList.fromListUnsafe(errs.map(_.toString).toList))

  private def toJsonFile[T: Writes](
    t: T,
    file: File
  ): Try[Unit] =
    Using(new FileWriter(file))(_.write(Json.toJson(t) pipe Json.stringify))


  override def submissionReports(
    site: Code[Site],
    useCase: UseCase.Value,
    filter: Submission.Report.Filter = Submission.Report.Filter(None,None,None,None)
  )(
    implicit ctx: ExecutionContext
  ): Future[EitherNel[String,Seq[Submission.Report]]] = {

    val file = submissionReportsFile(site,useCase)

    if (file.exists){
      log.debug(s"SubmissionReports File $file exists, reading...")
      Future.successful(fromJsonFile[Collection[Submission.Report]](file).map(_.entries))
    } else 
      request(site,s"${useCase.toString.toLowerCase}/peer2peer/mvh/submission-reports")
        .pipe {  
          req => Seq(
            filter.period.map(_.start).map("created-after" -> ISO_LOCAL_DATE_TIME.format(_)),
            filter.period.flatMap(_.endOption.map("created-before" -> ISO_LOCAL_DATE_TIME.format(_))),
            filter.status.map("status" -> _.mkString(",")),
            filter.`type`.map("type" -> _.mkString(","))
          )
          .flatten
          .foldLeft(req)((r,param) => r.addQueryStringParameters(param))
        }
        .get()
        .map(
          response => response.status match { 
            case 200    => response.body[JsValue].as[Collection[Submission.Report]].asRight
            case status => s"Site $site: Failed to get $useCase SubmissionReports - $status ${response.statusText}\n${response.body}".asLeft
          }
        )
        .andThen { 
          case Success(Right(submissionReports)) => toJsonFile(submissionReports,file)
        }
        .map(
          _.map(_.entries).toEitherNel
        )

  }


  override def submission[T: Format](
    report: Submission.Report,
    projections: Seq[String] = Nil
  )(
    implicit ctx: ExecutionContext
  ): Future[EitherNel[String,T]] = {

    val tan     = report.id
    val useCase = report.useCase
    val site    = report.site.code
    val file    = submissionFile(tan,site,useCase)

    if (file.exists){
      log.debug(s"Submission File $file exists, reading...")
      Future.successful(fromJsonFile[T](file))
    } else 
      request(site,s"${useCase.toString.toLowerCase}/peer2peer/mvh/submissions/$tan")
        .pipe(
          req =>
            if (projections.nonEmpty) req.addQueryStringParameters("project" -> projections.mkString(","))
            else req
        )
        .get()
        .map(
          response => response.status match { 
            case 200    => response.body[JsValue].as[T].asRight
            case status => s"Site $site: Failed to get $useCase Submission $tan - $status ${response.statusText}\n${response.body}".asLeft
          }
        )
        .andThen { 
          case Success(Right(submission)) => toJsonFile(submission,file)
        }
        .map(
          _.toEitherNel
        )
  }

}
