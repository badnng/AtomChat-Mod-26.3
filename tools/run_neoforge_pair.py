"""Run two isolated, hidden NeoForge clients against a real loopback integrated server.

Gradle prepares the development launch once, then exits before either client starts.
The exported launch specification is reused by two Java processes, avoiding Gradle's
project lock. Reports retain explicit FAIL/SKIP checks; --allow-partial is opt-in.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import locale
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid
import zipfile


EXPORT_INIT = r"""
import groovy.json.JsonOutput
gradle.projectsEvaluated {
    rootProject.tasks.named('runAutomation').configure { run ->
        dependsOn(rootProject.tasks.named('createAutomationLaunchScript'))
        doFirst {
            // Match ModDev 2.0.148 CreateLaunchScriptTask.createScripts exactly.
            // JavaExec.commandLine is a generic ExecSpec view, not the JVM command.
            def launch = rootProject.tasks.named('createAutomationLaunchScript').get()
            def invocation = [
                launch.javaExecutable.get(),
                '@' + launch.classpathArgsFile.get().asFile.absolutePath,
                '@' + launch.vmArgsFile.get(),
                launch.modFolders.get().getArgument(),
                run.mainClass.get(),
                '@' + launch.programArgsFile.get()
            ]
            def destination = new File(System.getProperty('atomchat.pair.launchSpec'))
            destination.parentFile.mkdirs()
            destination.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
                schema: 2,
                commandLine: invocation.collect { it.toString() },
                environment: launch.environment.get(),
                workingDirectory: run.gameDirectory.get().asFile.absolutePath
            ])), 'UTF-8')
            throw new org.gradle.api.tasks.StopExecutionException('Pair launch exported; clients are started outside Gradle')
        }
    }
}
"""


def flatten_argfiles(arguments: list[str], cwd: Path) -> list[str]:
    """Expand Gradle's Java launcher files before writing one role-specific file."""
    result: list[str] = []
    for argument in arguments:
        if argument.startswith('@') and not argument.startswith('@@'):
            path = Path(argument[1:])
            if not path.is_absolute():
                path = cwd / path
            try:
                contents = path.read_text(encoding='utf-8-sig')
            except UnicodeDecodeError:
                # ModDev writes argfiles using the Gradle JVM's native Windows charset.
                contents = path.read_text(encoding=locale.getencoding())
            lexer = shlex.shlex(contents, posix=True)
            lexer.whitespace_split = True
            lexer.commenters = '#'
            result.extend(flatten_argfiles(list(lexer), path.parent))
        else:
            result.append(argument)
    return result


def java_quote(argument: str) -> str:
    # Java launcher argfile grammar, independent of cmd.exe/PowerShell quoting.
    return '"' + argument.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'


def validate_launch(spec: dict) -> None:
    """Reject incomplete exports before starting Minecraft or claiming preparation success."""
    command = spec.get('commandLine', [])
    if not command or command[0] in ('null', '', None):
        raise ValueError('Launch has no Java executable; re-export with current runner')
    if not Path(command[0]).is_file():
        raise ValueError('Exported Java executable is missing: ' + command[0])
    args = flatten_argfiles(command[1:], Path(spec['workingDirectory']))
    classpath = None
    for index, argument in enumerate(args[:-1]):
        if argument in ('-cp', '-classpath', '--class-path'):
            classpath = args[index + 1]
    if not classpath or 'net.neoforged.devlaunch.Main' not in args:
        raise ValueError('Export is missing the runtime classpath or ModDev launch main')
    required = {'net/neoforged/devlaunch/Main.class', 'net/neoforged/fml/startup/Client.class'}
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if path.is_dir():
            required -= {name for name in required if (path / name).is_file()}
        elif path.is_file() and path.suffix == '.jar':
            with zipfile.ZipFile(path) as archive:
                required -= set(archive.namelist())
        if not required:
            print('Launch validation: ModDev main and NeoForge Client classes found on runtime classpath', flush=True)
            return
    raise ValueError('Incomplete runtime classpath; missing: ' + ', '.join(sorted(required)))


def artifact_launch(spec: dict, mod_jar: Path) -> tuple[dict, dict]:
    """Use FML's supported grouped JarContents path, with libraries supplied by JarJar."""
    mod_jar = mod_jar.resolve(strict=True)
    dependencies = {}
    with zipfile.ZipFile(mod_jar) as archive:
        names = set(archive.namelist())
        if any('automation' in name.lower() for name in names):
            raise ValueError('Published mod JAR contains automation entries')
        if not {'META-INF/neoforge.mods.toml', 'com/atom/chat/AtomChat.class', 'META-INF/jarjar/metadata.json'} <= names:
            raise ValueError('Expected a bundled NeoForge AtomChat release JAR')
        metadata = json.loads(archive.read('META-INF/jarjar/metadata.json'))
        for item in metadata.get('jars', []):
            path = item['path']
            payload = archive.read(path)
            with zipfile.ZipFile(io.BytesIO(payload)) as nested:
                if not nested.namelist():
                    raise ValueError('Empty nested dependency: ' + path)
            dependencies[Path(path).name] = hashlib.sha256(payload).hexdigest()
    for expected in ('skija-shared-', 'skija-windows-x64-' if os.name == 'nt' else 'skija-linux-x64-', 'types-', 'flatlaf-atomchat-'):
        if not any(name.startswith(expected) for name in dependencies):
            raise ValueError('Release JAR is missing required JarJar dependency: ' + expected)
    result = dict(spec)
    result['environment'] = dict(spec.get('environment', {}))
    args = flatten_argfiles(spec['commandLine'][1:], Path(spec['workingDirectory']))
    coordinate_args = [arg for arg in args if arg.startswith('-Dfml.modFolders=')]
    if len(coordinate_args) != 1:
        raise ValueError('Expected exactly one FML mod-folder definition in launch')
    coordinates = coordinate_args[0].split('=', 1)[1].split(os.pathsep)
    main_outputs = [entry.partition('%%')[2] for entry in coordinates if entry.partition('%%')[0] == 'atomchat']
    if not main_outputs:
        raise ValueError('Launch does not contain the AtomChat development group')
    remaining = [entry for entry in coordinates if entry.partition('%%')[0] != 'atomchat']
    if not any('automation' in entry.lower() for entry in remaining):
        raise ValueError('Automation development mod must remain separate from the release JAR')
    replacement = os.pathsep.join(remaining + ['atomchat%%' + str(mod_jar)])
    main_paths = {os.path.normcase(os.path.normpath(path)) for path in main_outputs}
    removed = []
    for index, arg in enumerate(args):
        if arg in ('-cp', '-classpath', '--class-path'):
            kept = []
            for entry in args[index + 1].split(os.pathsep):
                name = Path(entry).name.lower()
                external_nested = name.startswith(('skija-shared-', 'skija-windows-x64-', 'skija-linux-x64-', 'types-', 'flatlaf-')) and name.endswith('.jar')
                if os.path.normcase(os.path.normpath(entry)) in main_paths or external_nested:
                    removed.append(entry)
                else:
                    kept.append(entry)
            args[index + 1] = os.pathsep.join(kept)
        elif arg.startswith('-Dfml.modFolders='):
            args[index] = '-Dfml.modFolders=' + replacement
    # FML gives MOD_CLASSES precedence over its property; explicitly keep both aligned.
    result['environment']['MOD_CLASSES'] = replacement
    result['commandLine'] = [spec['commandLine'][0]] + args
    evidence = {'artifactMode': 'published-jar', 'modJar': str(mod_jar),
                'sha256': hashlib.sha256(mod_jar.read_bytes()).hexdigest(),
                'nestedDependencies': dependencies, 'removedClasspath': removed,
                'removedMainOutputs': main_outputs}
    if not any(Path(path).name.lower().startswith('skija-shared-') for path in removed):
        raise ValueError('No external Skija dependency was removed; launch isolation is unproven')
    return result, evidence


def role_command(spec: dict, role: str, game: Path, control: Path,
                 report: Path, timeout: int, java_home: Path | None,
                 artifact_manifest: Path | None = None) -> list[str]:
    exported = list(spec['commandLine'])
    executable = str(java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else exported[0]
    arguments = flatten_argfiles(exported[1:], Path(spec['workingDirectory']))
    overrides = {
        'atomchat.automation': 'true',
        'atomchat.automation.hideWindow': 'true',
        'atomchat.automation.pairRole': role,
        'atomchat.automation.controlDir': str(control),
        'atomchat.automation.report': str(report),
        'atomchat.automation.timeoutSeconds': str(timeout),
        'atomchat.automation.artifactMode': 'published-jar' if artifact_manifest else 'development-classes',
        'atomchat.automation.artifactManifest': str(artifact_manifest) if artifact_manifest else '',
    }
    arguments = [arg for arg in arguments if not any(arg.startswith('-D' + key + '=') for key in overrides)]
    # Avoid carrying single-client identity or a stale game directory into this run.
    clean: list[str] = []
    cursor = 0
    while cursor < len(arguments):
        if arguments[cursor] in ('--username', '--uuid', '--gameDir'):
            cursor += 2
        else:
            clean.append(arguments[cursor])
            cursor += 1
    name = 'AtomPairHost' if role == 'host' else 'AtomPairPeer'
    # Minecraft's offline identity: Java UUID.nameUUIDFromBytes("OfflinePlayer:"+name).
    identity = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))
    clean += ['--username', name, '--uuid', identity, '--gameDir', str(game)]
    role_args = ['-D' + key + '=' + value for key, value in overrides.items()] + clean
    argfile = game / 'java-arguments.txt'
    argfile.write_text('\n'.join(java_quote(arg) for arg in role_args) + '\n', encoding=locale.getencoding())
    return [executable, '@' + str(argfile)]


def kill_tree(process: subprocess.Popen, output) -> None:
    if process.poll() is not None:
        return
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], stdout=output,
                       stderr=subprocess.STDOUT, check=False, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        process.kill()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, help='JDK 25 installation')
    parser.add_argument('--timeout', type=int, default=480, help='Deadline for each client (seconds)')
    parser.add_argument('--prepare-timeout', type=int, default=600)
    parser.add_argument('--allow-partial', action='store_true', help='Accept explicit SKIP checks, never failures')
    parser.add_argument('--launch-spec', type=Path, help='Reuse an already exported development launch JSON')
    parser.add_argument('--prepare-only', action='store_true', help='Export the development launch and exit')
    parser.add_argument('--mod-jar', type=Path, help='Load this published AtomChat JAR and its nested libraries instead of development main output')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    project = root / 'platforms' / '26.3-neoforge'
    run = project / 'build' / ('automation-pair-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    run.mkdir(parents=True, exist_ok=False)
    control = run / 'control'
    control.mkdir()
    environment = os.environ.copy()
    java_home = args.java_home.resolve() if args.java_home else None
    if java_home:
        environment['JAVA_HOME'] = str(java_home)
        environment['PATH'] = str(java_home / 'bin') + os.pathsep + environment.get('PATH', '')
    print('Pair automation directory:', run, flush=True)
    spec_path = args.launch_spec.resolve() if args.launch_spec else run / 'launch.json'
    if not args.launch_spec:
        init = run / 'export-launch.init.gradle'
        init.write_text(EXPORT_INIT, encoding='utf-8')
        prepared_game = run / 'automation-export'
        wrapper = project / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        command = [str(wrapper), 'runAutomation', '--no-daemon', '--no-configuration-cache', '--console=plain', '-I', str(init),
                   '-Datomchat.pair.launchSpec=' + str(spec_path),
                   '-Patomchat.automation.gameDir=' + str(prepared_game),
                   '-Patomchat.automation.report=' + str(prepared_game / 'report.json')]
        with (run / 'prepare.log').open('w', encoding='utf-8') as output:
            process = subprocess.Popen(command, cwd=project, env=environment, stdout=output, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                code = process.wait(timeout=args.prepare_timeout)
            except subprocess.TimeoutExpired:
                kill_tree(process, output)
                print('FAIL: Gradle launch preparation timed out; inspect', run / 'prepare.log', file=sys.stderr)
                return 2
        if code or not spec_path.is_file():
            print('FAIL: development launch export failed; inspect', run / 'prepare.log', file=sys.stderr)
            return 2
    try:
        spec = json.loads(spec_path.read_text(encoding='utf-8-sig'))
        artifact = {'artifactMode': 'development-classes'}
        artifact_manifest = None
        if args.mod_jar:
            spec, artifact = artifact_launch(spec, args.mod_jar)
            artifact_manifest = run / 'artifact.json'
            artifact_manifest.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding='utf-8')
            (run / 'artifact-launch.json').write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding='utf-8')
        validate_launch(spec)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print('FAIL: invalid development launch:', error, file=sys.stderr)
        return 2
    if args.prepare_only:
        print('Launch specification:', spec_path)
        return 0
    processes = {}
    outputs = {}
    reports = {}
    started = time.time()
    try:
        for role in ('host', 'peer'):
            game = run / ('automation-' + role)
            game.mkdir()
            reports[role] = game / 'report.json'
            command = role_command(spec, role, game, control, reports[role], args.timeout, java_home, artifact_manifest)
            outputs[role] = (game / 'process.log').open('w', encoding='utf-8')
            role_environment = environment.copy()
            role_environment.update(spec.get('environment', {}))
            processes[role] = subprocess.Popen(command, cwd=game, env=role_environment,
                                                stdout=outputs[role], stderr=subprocess.STDOUT,
                                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            print(role + ' client PID:', processes[role].pid, flush=True)
        deadline = time.monotonic() + args.timeout + 30
        while any(process.poll() is None for process in processes.values()):
            if time.monotonic() >= deadline:
                print('FAIL: pair process deadline exceeded', file=sys.stderr)
                for role, process in processes.items():
                    kill_tree(process, outputs[role])
                return 2
            time.sleep(0.5)
    except (OSError, ValueError) as error:
        print('FAIL: could not launch pair:', error, file=sys.stderr)
        for role, process in processes.items():
            kill_tree(process, outputs[role])
        return 2
    finally:
        for output in outputs.values():
            output.close()
    failed = False
    partial = False
    combined = {'schema': 1, 'status': 'RUNNING', 'artifact': artifact, 'roles': {}}
    for role in ('host', 'peer'):
        report = reports[role]
        try:
            if not report.is_file() or report.stat().st_mtime < started - 2:
                raise ValueError('No fresh client report')
            data = json.loads(report.read_text(encoding='utf-8'))
            combined['roles'][role] = data
            checks = data.get('checks', [])
            for check in checks:
                print(f"{role:4} {check['status']:5} {check['name']}: {check['detail']}")
            status = data.get('status')
            role_failed = (processes[role].returncode != 0 or status not in ('PASS', 'PARTIAL')
                           or not checks or any(c.get('status') == 'FAIL' for c in checks))
            failed |= role_failed
            partial |= status == 'PARTIAL' or any(c.get('status') == 'SKIP' for c in checks)
            print(f"{role} overall: {status}; Java exit: {processes[role].returncode}; report: {report}")
        except (OSError, ValueError, KeyError) as error:
            failed = True
            combined['roles'][role] = {'status': 'FAIL', 'detail': str(error)}
            print(f'FAIL: {role}: {error}; inspect {report.parent / "process.log"}', file=sys.stderr)
    combined['status'] = 'FAIL' if failed else 'PARTIAL' if partial else 'PASS'
    summary = run / 'pair-report.json'
    summary.write_text(json.dumps(combined, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Pair overall:', combined['status'], '; report:', summary)
    return 2 if failed else 3 if partial and not args.allow_partial else 0


if __name__ == '__main__':
    raise SystemExit(main())
