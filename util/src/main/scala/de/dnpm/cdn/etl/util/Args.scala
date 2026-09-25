package de.dnpm.cdn.etl.util


object Args
{

  implicit class Ops(val args: Array[String]) extends AnyVal
  {
    def value(name: String): Option[String] =
      args.find(_ startsWith s"--$name")
        .map(arg => arg.substring(arg.indexOf("=")+1))
  }
}
