File buildLog = new File(basedir, "build.log")
assert buildLog.exists()

String logContent = buildLog.text

assert logContent.contains("org.apache.commons:commons-lang3:3.12.0 -> 1 reference") :
        "Expected 1 reference for commons-lang3."
assert !logContent.contains("org.ow2.asm:asm:9.10 ->") :
        "Excluded dependency org.ow2.asm:asm:9.10 must not appear in results."

return true
