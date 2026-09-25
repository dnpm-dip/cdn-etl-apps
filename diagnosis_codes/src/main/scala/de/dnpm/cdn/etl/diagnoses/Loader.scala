package de.dnpm.cdn.etl.diagnoses


import java.io.{
  File,
  FileWriter
}
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import scala.concurrent.{
  ExecutionContext,
  Future
}
import scala.util.Using
import cats.data.IorNel
import cats.syntax.either._
import cats.syntax.ior._
import cats.syntax.traverse._
import de.dnpm.dip.util.Logging
import de.dnpm.dip.coding.Code
import de.dnpm.dip.coding.icd.ICD10GM
import de.dnpm.dip.rd.model.{
  Orphanet,
  AlphaIDSE
}
import de.dnpm.dip.service.mvh.UseCase
import de.dnpm.bfarm.model.base.LibraryType


final case class Result
(
  useCase: UseCase.Value,
  submissionDate: LocalDate,
  libraryType: LibraryType.Value,
  icd10Codes: Option[List[Code[ICD10GM]]], 
  orphaCodes: Option[List[Code[Orphanet]]], 
  alphaIdCodes: Option[List[Code[AlphaIDSE]]] 
)


trait Loader[F[_],Ctx]
{
  def process(
    results: Seq[Result]
  )(
    implicit ctx: Ctx
  ): F[IorNel[String,Seq[Result]]]
}

object TSV
{

  val headers =
    s"${List("submissionDate","libraryType","icd-10-code","orpha-code","alpha-id").mkString("\t")}\n"

  def apply(result: Result): String =
    s"${
      List(
        ISO_LOCAL_DATE.format(result.submissionDate),
        result.libraryType.toString,
        result.icd10Codes.map(_.mkString("|")).getOrElse("N/A"),
        result.orphaCodes.map(_.mkString("|")).getOrElse("N/A"),
        result.alphaIdCodes.map(_.mkString("|")).getOrElse("N/A")
      )
      .mkString("\t") 
    }\n"

}

class TSVLoader(
  dir: File
)
extends Loader[Future,ExecutionContext]
with Logging
{

  dir.mkdirs

  private def outFile(useCase: UseCase.Value): File =
    new File(dir,s"${useCase}_Abfrage_09-2026.csv")


  override def process(
    results: Seq[Result]
  )(
    implicit ctx: ExecutionContext
  ): Future[IorNel[String,Seq[Result]]] = {
    Future(
      results.groupBy(_.useCase)
        .map {
          case (useCase,useCaseResults) => useCase -> (outFile(useCase),useCaseResults.sortBy(_.submissionDate))
        }
        .map {
          case (useCase,(file,useCaseResults)) =>

            log.info(s"Writing $useCase results to TSV file $file")

            Using(new FileWriter(outFile(useCase))){ w =>
              w.write(TSV.headers)
              useCaseResults
                .toList
                .traverse(result =>
                  Either.catchNonFatal{
                    w.write(TSV(result))
                    result
                  }
                  .leftMap(_.getMessage)
                  .toIor
                  .toIorNel
                )
            }
            .fold(
              t => t.getMessage.leftIor.toIorNel,
              identity
            ) 
        }
        .foldLeft(List.empty[Result].rightIor[String].toIorNel){ 
          _ combine _ 
        }
    )

  }
  
}

