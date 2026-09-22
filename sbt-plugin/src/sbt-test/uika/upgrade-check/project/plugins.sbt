sys.props.get("plugin.version") match {
  case Some(version) => addSbtPlugin("net.exoego.uika" % "sbt-uika" % version)
  case None => sys.error("plugin.version is not set")
}

// The stub CLI jar comes from the core test suite's StubCli, so the stub has one
// implementation across the builds. Compiled here because build.sbt is what calls it.
sys.props.get("uika.stub.source") match {
  case Some(source) => Compile / unmanagedSources += file(source)
  case None => sys.error("uika.stub.source is not set")
}
