"""Smoke-test AtomChat in the real, headless NeoForge dedicated-server distribution.

Exports ModDev's runServer launch, then starts Java after Gradle has exited. Only
main mod development outputs are loaded; no automation mod or GameTest server is
used. A fresh build/automation-dedicated-* fixture contains the world, EULA,
properties, launch files, logs, and JSON report. This does not test a packaged JAR.
"""
from __future__ import annotations

import argparse
import json
import locale
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time
import zipfile

from run_neoforge_pair import flatten_argfiles, java_quote, kill_tree


EXPORT_INIT = r"""
import groovy.json.JsonOutput
gradle.projectsEvaluated {
    def fixture = new File(System.getProperty('atomchat.serverSmoke.gameDir'))
    rootProject.neoForge.runs.getByName('server').gameDirectory.set(fixture)
    rootProject.tasks.named('runServer').configure { run ->
        dependsOn(rootProject.tasks.named('createServerLaunchScript'))
        doFirst {
            // Follow ModDev 2.0.148 CreateLaunchScriptTask.createScripts.
            def launch = rootProject.tasks.named('createServerLaunchScript').get()
            def invocation = [
                launch.javaExecutable.get(),
                '@' + launch.classpathArgsFile.get().asFile.absolutePath,
                '@' + launch.vmArgsFile.get(),
                launch.modFolders.get().getArgument(),
                run.mainClass.get(),
                '@' + launch.programArgsFile.get()
            ]
            def destination = new File(System.getProperty('atomchat.serverSmoke.launchSpec'))
            destination.parentFile.mkdirs()
            destination.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
                schema: 1,
                distribution: 'DEDICATED_SERVER',
                artifactMode: 'development-main-outputs',
                commandLine: invocation.collect { it.toString() },
                environment: launch.environment.get(),
                workingDirectory: run.gameDirectory.get().asFile.absolutePath
            ])), 'UTF-8')
            throw new org.gradle.api.tasks.StopExecutionException('Dedicated-server launch exported')
        }
    }
}
"""


def validate_launch(spec: dict) -> list[str]:
    command = spec['commandLine']
    if not command or not Path(command[0]).is_file():
        raise ValueError('Missing exported Java executable')
    arguments = flatten_argfiles(command[1:], Path(spec['workingDirectory']))
    if 'net.neoforged.devlaunch.Main' not in arguments:
        raise ValueError('Missing actual ModDev launch main')
    if 'net.neoforged.fml.startup.Server' not in arguments or '--nogui' not in arguments:
        raise ValueError('Launch is not the dedicated-server distribution with --nogui')
    if any('atomchat.automation' in item or 'atomchatAutomation' in item for item in arguments):
        raise ValueError('Automation mod or automation properties leaked into server launch')
    folders = [item.split('=', 1)[1] for item in arguments if item.startswith('-Dfml.modFolders=')]
    if len(folders) != 1 or not folders[0]:
        raise ValueError('Missing main-mod development folders')
    for folder in folders[0].split(os.pathsep):
        mod, separator, path = folder.partition('%%')
        if mod != 'atomchat' or not separator or not Path(path).is_dir():
            raise ValueError('Unexpected or missing mod folder: ' + folder)
    classpath = next((arguments[i + 1] for i, item in enumerate(arguments[:-1])
                      if item in ('-cp', '-classpath', '--class-path')), None)
    if not classpath:
        raise ValueError('Missing runtime classpath')
    required = {'net/neoforged/devlaunch/Main.class', 'net/neoforged/fml/startup/Server.class'}
    for item in classpath.split(os.pathsep):
        path = Path(item)
        if path.is_dir():
            required -= {name for name in required if (path / name).is_file()}
        elif path.is_file() and path.suffix == '.jar':
            with zipfile.ZipFile(path) as archive:
                required -= set(archive.namelist())
    if required:
        raise ValueError('Runtime classpath missing: ' + ', '.join(sorted(required)))
    return arguments


def consume_output(process: subprocess.Popen, output, lines: queue.Queue) -> None:
    try:
        for line in process.stdout:
            output.write(line)
            output.flush()
            lines.put(line.rstrip())
    finally:
        lines.put(None)


def await_line(lines: queue.Queue, process: subprocess.Popen, pattern: str,
               timeout: int, transcript: list[str]) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            line = lines.get(timeout=min(0.5, max(0.01, deadline - time.monotonic())))
        except queue.Empty:
            if process.poll() is not None:
                raise RuntimeError('Server exited before expected output: ' + pattern)
            continue
        if line is None:
            raise RuntimeError('Server output ended before expected output: ' + pattern)
        transcript.append(line)
        if re.search(pattern, line):
            return line
    raise TimeoutError('Timed out waiting for server output: ' + pattern)


def send(process: subprocess.Popen, command: str, commands: list[dict]) -> None:
    process.stdin.write(command + '\n')
    process.stdin.flush()
    commands.append({'command': command, 'sentAt': time.time()})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, help='JDK 25 installation')
    parser.add_argument('--timeout', type=int, default=240, help='Server startup deadline in seconds')
    parser.add_argument('--command-timeout', type=int, default=30)
    parser.add_argument('--stop-timeout', type=int, default=60)
    parser.add_argument('--prepare-timeout', type=int, default=600)
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--launch-spec', type=Path, help='Reuse a previously exported runServer launch')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    project = root / 'platforms' / '26.3-neoforge'
    run = project / 'build' / ('automation-dedicated-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    run.mkdir(parents=True, exist_ok=False)
    game = run / 'automation-server'
    game.mkdir()
    report_path = run / 'server-report.json'
    report = {'schema': 1, 'status': 'RUNNING', 'distribution': 'DEDICATED_SERVER',
              'artifactMode': 'development-main-outputs', 'packagedJarTested': False,
              'gameDirectory': str(game), 'checks': [], 'commands': [], 'startedAt': time.time()}

    def check(name: str, success: bool, detail: str) -> None:
        report['checks'].append({'name': name, 'status': 'PASS' if success else 'FAIL', 'detail': detail})
        print(('PASS' if success else 'FAIL') + ': ' + name + ': ' + detail, flush=True)

    environment = os.environ.copy()
    java_home = args.java_home.resolve() if args.java_home else None
    if java_home:
        environment['JAVA_HOME'] = str(java_home)
        environment['PATH'] = str(java_home / 'bin') + os.pathsep + environment.get('PATH', '')
    print('Dedicated-server smoke directory:', run, flush=True)
    process = None
    reader = None
    output = None
    transcript: list[str] = []
    try:
        spec_path = args.launch_spec.resolve() if args.launch_spec else run / 'launch.json'
        if not args.launch_spec:
            init = run / 'export-server-launch.init.gradle'
            init.write_text(EXPORT_INIT, encoding='utf-8')
            wrapper = project / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
            command = [str(wrapper), 'runServer', '--no-daemon', '--no-configuration-cache', '--console=plain',
                       '-I', str(init), '-Datomchat.serverSmoke.gameDir=' + str(game),
                       '-Datomchat.serverSmoke.launchSpec=' + str(spec_path)]
            with (run / 'prepare.log').open('w', encoding='utf-8') as prepared:
                preparation = subprocess.Popen(command, cwd=project, env=environment, stdout=prepared,
                                               stderr=subprocess.STDOUT,
                                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
                try:
                    code = preparation.wait(timeout=args.prepare_timeout)
                except subprocess.TimeoutExpired:
                    kill_tree(preparation, prepared)
                    raise TimeoutError('Gradle launch preparation timed out; inspect prepare.log')
            if code or not spec_path.is_file():
                raise RuntimeError('Gradle launch export failed; inspect prepare.log')
        spec = json.loads(spec_path.read_text(encoding='utf-8-sig'))
        arguments = validate_launch(spec)
        check('dedicated-launch-mainmod-only', True,
              'Actual ModDev dedicated Server entry point, --nogui and only atomchat development mod folders')
        if args.prepare_only:
            report['status'] = 'PREPARED'
            print('Launch specification:', spec_path, flush=True)
            return 0
        # All acceptance and server configuration are scoped to this fresh fixture.
        (game / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
        (game / 'server.properties').write_text(
            'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n'
            'enable-query=false\nenable-rcon=false\nlevel-name=smoke-world\n'
            'level-seed=2603\nlevel-type=minecraft:flat\ngenerate-structures=false\n'
            'view-distance=2\nsimulation-distance=2\nmax-players=2\n'
            'pause-when-empty-seconds=0\nspawn-protection=0\nmotd=AtomChat dedicated smoke fixture\n',
            encoding='utf-8')
        # Do not carry an old exported working directory or automation switch into this launch.
        arguments = [item for item in arguments if not item.startswith(('-Djava.awt.headless=',
                     '-Dfile.encoding=', '-Dstdout.encoding=', '-Dstderr.encoding='))]
        arguments = ['-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                     '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8'] + arguments
        argfile = game / 'java-arguments.txt'
        argfile.write_text('\n'.join(java_quote(item) for item in arguments) + '\n', encoding=locale.getencoding())
        executable = java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if java_home else spec['commandLine'][0]
        environment.update(spec.get('environment', {}))
        output = (run / 'process.log').open('w', encoding='utf-8')
        process = subprocess.Popen([str(executable), '@' + str(argfile)], cwd=game, env=environment,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace', bufsize=1,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        report['pid'] = process.pid
        report['javaExecutable'] = str(executable)
        report['headless'] = True
        lines: queue.Queue = queue.Queue()
        reader = threading.Thread(target=consume_output, args=(process, output, lines), daemon=True)
        reader.start()
        done = await_line(lines, process, r'Done \(.*\)! For help, type', args.timeout, transcript)
        check('dedicated-server-startup', True, done)
        check('atomchat-loaded', any('AtomChat initialized' in line for line in transcript),
              'Common entry point initialized on dedicated-server launch')
        check('config-command-registered', any('AtomChat config screen registered (server side)' in line for line in transcript),
              'Production config command registration event ran')
        send(process, 'list', report['commands'])
        response = await_line(lines, process, r'There are 0 of a max of 2 players online', args.command_timeout, transcript)
        check('console-list', True, response)
        send(process, 'atomchat gui', report['commands'])
        response = await_line(lines, process,
                              r'atomchat\.server\.console_only|Run this in game as a player|该命令需要在游戏内以玩家身份执行',
                              args.command_timeout, transcript)
        check('config-console-player-guard', True, response)
        send(process, 'stop', report['commands'])
        process.wait(timeout=args.stop_timeout)
        reader.join(timeout=5)
        report['javaExitCode'] = process.returncode
        text = (run / 'process.log').read_text(encoding='utf-8')
        check('clean-shutdown', process.returncode == 0 and 'Stopping server' in text,
              'stop command completed; Java exit=' + str(process.returncode))
        check('no-distribution-load-error', not any(marker in text for marker in (
              'Attempted to load class', 'Invalid dist', 'NoClassDefFoundError',
              'MixinApplyError', 'MixinTransformerError', 'HeadlessException')),
              'No client-distribution, Mixin transformation or AWT headless loading failure')
        report['status'] = 'PASS' if all(item['status'] == 'PASS' for item in report['checks']) else 'FAIL'
        return 0 if report['status'] == 'PASS' else 2
    except (OSError, ValueError, KeyError, RuntimeError, TimeoutError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        check('server-smoke-completion', False, str(error))
        report['status'] = 'FAIL'
        if process is not None and process.poll() is None:
            try:
                send(process, 'stop', report['commands'])
                process.wait(timeout=args.stop_timeout)
            except (OSError, subprocess.TimeoutExpired):
                kill_tree(process, output)
        return 2
    finally:
        if process is not None:
            report['javaExitCode'] = process.poll()
            if process.stdin:
                process.stdin.close()
        if reader is not None:
            reader.join(timeout=5)
        if output is not None:
            output.close()
        report['finishedAt'] = time.time()
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('Dedicated-server report:', report_path, flush=True)


if __name__ == '__main__':
    raise SystemExit(main())
