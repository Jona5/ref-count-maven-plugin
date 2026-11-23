File buildLog = new File(basedir, "build.log")
assert buildLog.exists()
String logContent = buildLog.text

// Field List<StringUtils> (1) + method return List<StringUtils> (1) = 2 references (improved from baseline 3!)
assert logContent.contains("org.apache.commons:commons-lang3:3.12.0 -> 2 references") :
        "Expected 2 generic references for commons-lang3."
assert logContent.contains("low usage") :
        "Expected low usage indicator."
assert logContent.contains("com.example.GenericApp -> org.apache.commons.lang3.StringUtils") :
        "Expected per-class reference detail output."
assert logContent.contains("Baseline improved: org.apache.commons:commons-lang3 (3 -> 2 references)") :
        "Expected baseline improvement message."

File mdReport = new File(basedir, "target/ref-count-report.md")
assert mdReport.isFile() : "Expected Markdown report at target/ref-count-report.md"
String mdContent = mdReport.text
assert mdContent.contains("| `org.apache.commons:commons-lang3:3.12.0` | compile | direct |") :
        "Expected dependency table row in Markdown report."
assert mdContent.contains("### Reference Details") :
        "Expected details section in Markdown report."

return true
