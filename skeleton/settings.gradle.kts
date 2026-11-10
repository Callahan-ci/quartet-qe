rootProject.name = "__NAME__"

// The Quartet QE framework, pinned as a git submodule.
// Set QUARTET_LOCAL to a working copy of quartet-qe to develop against it instead.
includeBuild(System.getenv("QUARTET_LOCAL") ?: ".quartet")
