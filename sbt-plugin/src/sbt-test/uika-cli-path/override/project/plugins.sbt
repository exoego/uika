sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("net.exoego.uika" % "sbt-uika" % version)
  case None => sys.error("plugin.version is not set")
}
