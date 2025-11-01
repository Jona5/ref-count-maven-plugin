File buildLog = new File(basedir, "build.log")
assert buildLog.exists()

String logContent = buildLog.text

// Check if the plugin correctly identified the reference to commons-lang3
boolean found = logContent.contains("org.apache.commons:commons-lang3:3.12.0 -> 1 references")

assert found : "The build log did not contain the expected reference count for commons-lang3."

// The check for touch.txt that was added previously
File touchFile = new File(basedir, "target/touch.txt")
assert touchFile.isFile() : "touch.txt was not created in the target directory."

return true
