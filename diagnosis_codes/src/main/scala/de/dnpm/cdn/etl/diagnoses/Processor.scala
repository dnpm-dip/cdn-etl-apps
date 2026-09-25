package de.dnpm.cdn.etl.diagnoses

import java.io.File
import scala.concurrent.{
  ExecutionContext,
  Future
}
import scala.util.chaining._
import cats.data.{
  Ior,
  IorNel
}
import cats.syntax.either._
import cats.syntax.ior._
import cats.syntax.traverse._
import de.dnpm.dip.coding.{
  Code,
  Coding
}
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.icd.ICD10GM
import de.dnpm.dip.model.{
  NGSReport,
  Site
}
import de.dnpm.dip.mtb.model.MTBDiagnosis
import de.dnpm.dip.rd.model.{
  AlphaIDSE,
  Orphanet,
  RDDiagnosis
}
import de.dnpm.dip.service.mvh.{
  Submission,
  UseCase
}
import UseCase.{MTB,RD}
import de.dnpm.bfarm.model.base.LibraryType
import de.dnpm.cdn.etl.util.{
  Args,
  Extractor,
  DIPNodeExtractor,
  SitesUseCases
}


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
        new TSVLoader(targetDir)
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
  loader: Loader[Future,ExecutionContext]
)(
  implicit ec: ExecutionContext
)
extends Logging
{

  private val libraryType: NGSReport.Type.Value => LibraryType.Value =
    Map(
      NGSReport.Type.GenomeLongRead  -> LibraryType.WGSLr,
      NGSReport.Type.GenomeShortRead -> LibraryType.WGS,
      NGSReport.Type.Exome           -> LibraryType.WES, 
      NGSReport.Type.Panel           -> LibraryType.Panel
    )
    .orElse {
      case _ => LibraryType.None
    }

  private def MTBResult(
    submissionReport: Submission.Report,
    submission: PartialSubmission[MTBDiagnosis]
  ): Result = {

    val diagnosisCodes = 
      submission.diagnoses
        .toList
        .partition(_.`type`.latestBy(_.date).value.code.enumValue == MTBDiagnosis.Type.Main)
        .pipe { case (mains,others) => mains.head :: others }
        .map(_.code.code)

    Result(
      MTB,
      submission.submittedAt.toLocalDate,
      submissionReport.sequencingType.map(libraryType).getOrElse(LibraryType.None),
      Some(diagnosisCodes),
      None, None, // Orphanet and Alpha-ID-SE codes not defined in MTB data set
    )
  }

  private def RDResult(
    submissionReport: Submission.Report,
    submission: PartialSubmission[RDDiagnosis]
  ): Result = {

    val diagnosisCodes =
      submission.diagnoses.flatMap(_.codes)
        .toList
        .groupBy(_.system)

    Result(
      RD,
      submission.submittedAt.toLocalDate,
      submissionReport.sequencingType.map(libraryType).getOrElse(LibraryType.None),
      diagnosisCodes.get(Coding.System[ICD10GM].uri).map(_.map(_.code.asInstanceOf[Code[ICD10GM]])),
      diagnosisCodes.get(Coding.System[Orphanet].uri).map(_.map(_.code.asInstanceOf[Code[Orphanet]])),
      diagnosisCodes.get(Coding.System[AlphaIDSE].uri).map(_.map(_.code.asInstanceOf[Code[AlphaIDSE]]))
    )
  }


  /**
   *  Per site and useCase:
   *  - Get SubmissionReports
   *  - Group by Patient and get latest
   *  - Get Submission
   *  - Transform to Result
   *  - Output to TSV
   */
  import de.dnpm.cdn.etl.util.Batching.syntax._

  def exec: Future[IorNel[String,Seq[Result]]] =
    for {
      latestSubmissionReports <- config.toList
        .sortBy(_._1.value) // Alphabetical sorting just for better log clarity
        .traverse {
          case (site,useCases) =>
            useCases.toList.traverse { useCase =>
        
              log.info(s"$site, $useCase: Getting SubmissionReports...")
        
              for {
                latestSubmissionReports <- extractor.submissionReports(site,useCase).map {
        
                  case Right(submissionReports) =>
                    submissionReports
                      .groupBy(_.patient)
                      .map(_._2.maxBy(_.createdAt))
                      .toList
        
                  case Left(errors) =>
                    log.error(s"${errors.toList.mkString(", ")}")
                    List.empty 
                }
                .recover { 
                  case t =>
                    log.error(s"$site, $useCase: Failure getting SubmissionReports",t)
                    List.empty 
                }
        
             } yield latestSubmissionReports
        
           }
           .map(_.flatten)
        }
        .map(_.flatten)

      // Traverse batch-wise:
      // Some sites are reached via the queueing/polling setup, running in an Apache Tomcat
      // which (by default) cannot process more than 200 parallel requests 
      results <- latestSubmissionReports.batchTraverse(10){ submissionReport =>

        log.debug(s"${submissionReport.site.code}, ${submissionReport.useCase}: Getting Submission ${submissionReport.id}...")
      
        submissionReport.useCase.pipe {
          case MTB =>
            for {
              outcome <- extractor.submission[PartialSubmission[MTBDiagnosis]](
                submissionReport,
                projections = List("submittedAt","diagnoses[*]")
              )
            } yield outcome.map(MTBResult(submissionReport,_))

          case RD =>
            for {
              outcome <- extractor.submission[PartialSubmission[RDDiagnosis]](
                submissionReport,
                List("submittedAt","diagnoses[*]")
              )
            } yield outcome.map(RDResult(submissionReport,_))
        }
        .recover { 
          case t =>
            s"${submissionReport.site.code}, ${submissionReport.useCase}: Failure Getting Submission ${submissionReport.id}"
              .tap(log.error(_,t))
              .asLeft
              .toEitherNel
        }
      }

      combinedResults = results.foldLeft(Seq.empty[Result].rightIor[String].toIorNel)(
        (acc,ior) => acc combine ior.map(Seq(_)).toIor
      )

      outcomes <- combinedResults match {
        case Ior.Right(res) =>
          log.info(s"${res.size} successful Results, exporting...")
          loader.process(res)

        case Ior.Both(errs,res) =>
          log.error(s"${res.size} successful Results (${errs.size} errors occurred), exporting...")
          errs.toList.foreach(log.error)
          loader.process(res)

        case left @ Ior.Left(errs) =>
          log.error(s"${errs.size} errors")
          errs.toList.foreach(log.error)
          Future.successful(left)
      }

    } yield outcomes

}
