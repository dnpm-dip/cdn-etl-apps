package de.dnpm.cdn.etl.util


import cats.data.EitherNel
import de.dnpm.dip.coding.Code
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.mvh.{
  Submission,
  UseCase
}
import play.api.libs.json.Format


trait Extractor[F[_],Ctx]
{

  def submissionReports(
    site: Code[Site],
    useCase: UseCase.Value,
    filter: Submission.Report.Filter = Submission.Report.Filter(None,None,None,None)
  )(
    implicit ctx: Ctx
  ): F[EitherNel[String,Seq[Submission.Report]]] 


  def submission[T: Format](
    report: Submission.Report,
    projections: Seq[String] = Nil
  )(
    implicit ctx: Ctx
  ): F[EitherNel[String,T]]

}
