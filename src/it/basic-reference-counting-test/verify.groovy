File buildLog = new File(basedir, "build.log")
assert buildLog.exists()

String logContent = buildLog.text

assert logContent.contains("org.apache.commons:commons-lang3:3.12.0 -> 2 references [direct,") :
        "Expected 2 references and [direct, ...] metadata for commons-lang3."
assert logContent.contains("1 dep class in 1 project class") :
        "Expected usage depth metrics (1 dep class in 1 project class)."
assert logContent.contains("org.ow2.asm:asm:9.10 -> 0 references [direct,") :
        "Expected unused dependency org.ow2.asm:asm:9.10 to be reported with 0 references."

return true
