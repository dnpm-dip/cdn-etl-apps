package de.dnpm.cdn.etl.util


import de.dnpm.dip.coding.Code
import de.dnpm.dip.model.Site
import de.dnpm.dip.service.mvh.UseCase
import UseCase.{MTB,RD}


object SitesUseCases
{

  def apply(): Map[Code[Site],Set[UseCase.Value]] = value

  private val value: Map[Code[Site],Set[UseCase.Value]] =
    Map(
      "Charité" -> Set(MTB,RD),
      "KKB"     -> Set(RD),
      "KUM"     -> Set(MTB,RD),
      "MHH"     -> Set(MTB,RD),
      "MRI"     -> Set(MTB,RD),
      "UKA"     -> Set(MTB,RD),
      "UKB"     -> Set(MTB,RD),
      "UKD"     -> Set(MTB,RD),
      "UKDD"    -> Set(RD),
      "UKE"     -> Set(MTB,RD),
      "UKER"    -> Set(MTB,RD),
      "UKFR"    -> Set(MTB,RD),
      "UKHD"    -> Set(MTB,RD),
      "UKJ"     -> Set(MTB,RD),
      "UKK"     -> Set(MTB,RD),
      "UKL"     -> Set(RD),
      "UKM"     -> Set(MTB,RD),
      "UKMR"    -> Set(MTB),
      "UKR"     -> Set(MTB,RD),
      "UKSH"    -> Set(MTB,RD),
      "UKT"     -> Set(MTB,RD),
      "UKU"     -> Set(MTB,RD),
      "UKW"     -> Set(MTB,RD),
      "UM"      -> Set(MTB,RD),
      "UME"     -> Set(RD),
      "UMG"     -> Set(MTB,RD),
      "UMH"     -> Set(MTB,RD)
    )
    .map {
      case (site,useCases) => Code(site) -> useCases
    }
}
