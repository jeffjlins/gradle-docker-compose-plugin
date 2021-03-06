package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.process.ExecSpec
import org.gradle.util.VersionNumber
import org.yaml.snakeyaml.Yaml

import java.util.concurrent.Executors

class ComposeExecutor {
    private final ComposeSettings extension
    private final Project project
    private final Logger logger

    ComposeExecutor(ComposeSettings settings) {
        this.extension = settings
        this.project = settings.project
        this.logger = settings.project.logger
    }

    private void executeWithCustomOutput(OutputStream os, String... args) {
        def ex = this.extension
        project.exec { ExecSpec e ->
            if (extension.dockerComposeWorkingDirectory) {
                e.setWorkingDir(extension.dockerComposeWorkingDirectory)
            }
            e.environment = ex.environment
            def finalArgs = [ex.executable]
            finalArgs.addAll(ex.useComposeFiles.collectMany { ['-f', it] })
            if (ex.projectName) {
                finalArgs.addAll(['-p', ex.projectName])
            }
            finalArgs.addAll(args)
            e.commandLine finalArgs
            e.standardOutput = os
        }
    }

    String execute(String... args) {
        new ByteArrayOutputStream().withStream { os ->
            executeWithCustomOutput(os, args)
            os.toString().trim()
        }
    }

    VersionNumber getVersion() {
        VersionNumber.parse(execute('--version').findAll(/(\d+\.){2}(\d+)/).head())
    }

    Iterable<String> getContainerIds(String serviceName) {
        execute('ps', '-q', serviceName).readLines()
    }

    void captureContainersOutput(Closure<Void> logMethod) {
        // execute daemon thread that executes `docker-compose logs -f --no-color`
        // the -f arguments means `follow` and so this command ends when docker-compose finishes
        def t = Executors.defaultThreadFactory().newThread(new Runnable() {
            @Override
            void run() {
                def os = new OutputStream() {
                    def buffer = new ArrayList<Byte>()

                    @Override
                    void write(int b) throws IOException {
                        // store bytes into buffer until end-of-line character is detected
                        if (b == 10 || b == 13) {
                            if (buffer.size() > 0) {
                                // convert the byte buffer to characters and print these characters
                                def toPrint = buffer.collect { it as byte }.toArray() as byte[]
                                logMethod(new String(toPrint))
                                buffer.clear()
                            }
                        } else {
                            buffer.add(b as Byte)
                        }
                    }
                }
                executeWithCustomOutput(os, 'logs', '-f', '--no-color')
            }
        })
        t.daemon = true
        t.start()
    }

    Iterable<String> getServiceNames() {
        if (version >= VersionNumber.parse('1.6.0')) {
            execute('config', '--services').readLines()
        } else {
            def composeFiles = extension.useComposeFiles.empty ? getStandardComposeFiles() : getCustomComposeFiles()
            composeFiles.collectMany { composeFile ->
                def compose = (Map<String, Object>) (new Yaml().load(project.file(composeFile).text))
                // if there is 'version' on top-level then information about services is in 'services' sub-tree
                compose.containsKey('version') ? ((Map) compose.get('services')).keySet() : compose.keySet()
            }.unique()

        }
    }

    Iterable<File> getStandardComposeFiles() {
        def res = []
        def f = findInParentDirectories('docker-compose.yml', project.projectDir)
        if (f != null) res.add(f)
        f = findInParentDirectories('docker-compose.override.yml', project.projectDir)
        if (f != null) res.add(f)
        res
    }

    Iterable<File> getCustomComposeFiles() {
        extension.useComposeFiles.collect {
            def f = project.file(it)
            if (!f.exists()) {
                throw new IllegalArgumentException("Custom Docker Compose file not found: $f")
            }
            f
        }
    }

    File findInParentDirectories(String filename, File directory) {
        if ((directory) == null) return null
        def f = new File(directory, filename)
        f.exists() ? f : findInParentDirectories(filename, directory.parentFile)
    }

}
