package de.dnpm.cdn.etl.submissions

import java.io.File
import scala.concurrent.{
  ExecutionContext,
  Future
}
import scala.util.chaining._
import cats.data.EitherNel
import cats.syntax.either._
import cats.syntax.traverse._
import de.dnpm.dip.coding.Code
import de.dnpm.dip.util.Logging
import de.dnpm.dip.util.mapping.syntax._
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.mvh.{
  Submission,
  UseCase
}
import UseCase._
import de.dnpm.dip.mtb.model.MTBPatientRecord
import de.dnpm.dip.rd.model.RDPatientRecord
import de.dnpm.bfarm.model.onco.OncologySubmission
import de.dnpm.bfarm.model.rd.RDSubmission
import de.dnpm.bfarm.model.onco.MTBMappings
import de.dnpm.bfarm.model.rd.RDMappings
import de.dnpm.cdn.etl.util.{
  Args,
  Extractor,
  DIPNodeExtractor,
  SitesUseCases
}
import play.api.libs.json.Reads


object Processor
{

  import ExecutionContext.Implicits.global

  def main(args: Array[String]): Unit = {

    import Args._

    val targetDir = new File(args.value("targetDir").getOrElse("./results"))

    val config =
      args.value("sites").map(_ split ",").map(_.toSet.map(Code[Site](_))) match { 
        case Some(sites) if sites.nonEmpty =>
          SitesUseCases().filter { case (site,_) => sites contains site }

        case _ => SitesUseCases()
      }

    val processor =
      new Processor(
        config,
        DIPNodeExtractor(args),
        new FileExporter(targetDir)
      )

    processor.exec.onComplete(
      _.fold(
       t => { t.printStackTrace; System.exit(1) },
       r => { System.exit(0) }
      )
    )

  }

}


class Processor(
  config: Map[Code[Site],Set[UseCase.Value]],
  extractor: Extractor[Future,ExecutionContext],
  exporter: Exporter[Future,ExecutionContext,Any]
)(
  implicit ec: ExecutionContext
)
extends Logging
{

  /**
   *  Per site and useCase:
   *  - Get FollowUp SubmissionReports
   *  - Get Submission
   *  - Apply mapping to BfArM schema
   *  - Export 
   */
  import de.dnpm.cdn.etl.util.Batching.syntax._

  // Tolerant Reads required to ensure that old submissions
  // in which the Consent wasn't check for structural correctness can also be processed
  implicit val tolerantMTBSubmission: Reads[Submission[MTBPatientRecord]] =
    Submission.tolerantReads.submission[MTBPatientRecord]
  
  implicit val tolerantRDSubmission: Reads[Submission[RDPatientRecord]] =
    Submission.tolerantReads.submission[RDPatientRecord]
      

  def exec: Future[Seq[EitherNel[String,Any]]] =
    for {
      submissionReports <- config.toList
        .sortBy(_._1.value) // Alphabetical sorting just for better log clarity
        .traverse {
          case (site,useCases) => useCases.toList.traverse { useCase =>
        
            log.info(s"$site, $useCase: Getting SubmissionReports...")
        
            extractor.submissionReports(
              site,
              useCase,
              Submission.Report.Filter(`type` = Some(Set(Submission.Type.FollowUp)))
            )
            .map {
              case Right(submissionReports) => submissionReports.sortBy(_.createdAt).takeRight(10)

              case Left(errors) => 
                log.error(s"${errors.toList.mkString(", ")}")
                List.empty 
            }
            .recover { 
              case t =>
                log.error(s"$site, $useCase: Failure getting SubmissionReports",t)
                List.empty 
            }
          }
          .map(_.flatten)
        }
        .map(_.flatten)

      // Traverse batch-wise:
      // Some sites are reached via the queueing/polling setup, running in an Apache Tomcat
      // which (by default) cannot process more than 200 parallel requests.
      // Also, given that some submission are very large, avoid overwhelming target DIP nodes 
      // by having too many parallel requests.
      results <- submissionReports.batchTraverse(5){ submissionReport =>

        log.debug(s"${submissionReport.site.code}, ${submissionReport.useCase}: Getting Submission ${submissionReport.id}...")

        submissionReport.useCase.pipe {
          case MTB =>

            import MTBMappings._

            for {
              extracted <- extractor.submission[Submission[MTBPatientRecord]](submissionReport)

              transformed = extracted.map(_.mapTo[OncologySubmission])

              exported <- transformed match {
                case Right(submission) => exporter.process(submission)

                case failed @ Left(errors) =>
                  log.error(s"${errors.toList.mkString(", ")}")
                  Future.successful(failed)
              }

            } yield exported


          case RD =>

            import RDMappings._

            for {
              extracted <- extractor.submission[Submission[RDPatientRecord]](submissionReport)

              transformed = extracted.map(_.mapTo[RDSubmission])

              exported <- transformed match {
                case Right(submission) => exporter.process(submission)

                case failed @ Left(errors) =>
                  log.error(s"${errors.toList.mkString(", ")}")
                  Future.successful(failed)
              }

            } yield exported

        }
        .recover { 
          case t =>
            s"${submissionReport.site.code}, ${submissionReport.useCase}: Failure processing Submission ${submissionReport.id}"
              .tap(log.error(_,t))
              .asLeft
              .toEitherNel
        }
      }

    } yield results

}
