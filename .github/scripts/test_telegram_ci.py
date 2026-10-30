"""Offline Telegram distribution regression tests; no requests leave this process."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

spec = importlib.util.spec_from_file_location('telegram_ci', Path(__file__).with_name('telegram_ci.py'))
tg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tg)

BASE_ENV = {'TELEGRAM_BOT_TOKEN': 'test-secret-token', 'TELEGRAM_CHAT_ID': '@test_channel'}


class TelegramTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, BASE_ENV, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        sleep_patch = patch.object(tg.time, 'sleep')
        self.sleep = sleep_patch.start()
        self.addCleanup(sleep_patch.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def info(self, target='1.21.1-fabric'):
        return dict(projectName='AtomChat', version='26.3', target=target, java=21,
                    commit='abcdef012345', branch='main', buildTime='2026-10-31',
                    repository='owner/repo', runUrl='https://github.com/owner/repo/actions/runs/1',
                    artifactUrl='https://github.com/owner/repo/actions/runs/1/artifacts/2',
                    commitSubject='Fix <unsafe> & render', runNumber='123')

    def target(self, target='1.21.1-fabric'):
        directory = self.root / target
        directory.mkdir()
        info = self.info(target)
        info['artifacts'] = []
        for platform in ('windows-x64', 'linux-x64'):
            filename = f'atomchat-{target}-{platform}.jar'
            content = b'bundled jar ' + platform.encode()
            (directory / filename).write_bytes(content)
            info['artifacts'].append(dict(fileName=filename, platform=platform,
                                          size=len(content), sha256=hashlib.sha256(content).hexdigest()))
        (directory / 'build-info.json').write_text(json.dumps(info), encoding='utf-8')
        return directory, info

    def rewrite(self, directory, info):
        (directory / 'build-info.json').write_text(json.dumps(info), encoding='utf-8')

    def test_missing_either_app_credential_falls_back_without_local_preparation(self):
        for app in ({}, {'TELEGRAM_API_ID': '123'}, {'TELEGRAM_API_HASH': 'a'*32},
                    {'TELEGRAM_API_ID': 'garbage', 'TELEGRAM_API_HASH': '   '},
                    {'TELEGRAM_API_ID': ' ', 'TELEGRAM_API_HASH': 'garbage'}):
            with self.subTest(app=app), patch.dict(os.environ, {**BASE_ENV, **app}, clear=True):
                self.assertEqual(tg.validate_config(), 'cloud')
        self.target()
        with patch.object(tg, 'publish_cloud') as cloud, patch.object(tg, 'local_server') as local:
            tg.main(self.root)
            self.assertEqual(cloud.call_count, 2)
            local.assert_not_called()

    def test_complete_credentials_are_validated(self):
        with patch.dict(os.environ, {'TELEGRAM_API_ID': '123', 'TELEGRAM_API_HASH': 'a'*32}):
            self.assertEqual(tg.validate_config(), 'local')
        for app in ({'TELEGRAM_API_ID': 'no', 'TELEGRAM_API_HASH': 'a'*32},
                    {'TELEGRAM_API_ID': '123', 'TELEGRAM_API_HASH': 'bad'}):
            with patch.dict(os.environ, app), self.assertRaises(ValueError):
                tg.validate_config()

    def test_config_output(self):
        output = self.root / 'output'
        with patch.dict(os.environ, {'GITHUB_OUTPUT': str(output)}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(tg.check_config(), 'cloud')
        self.assertEqual(output.read_text(), 'api_mode=cloud\n')

    def test_chat_id_and_safe_errors(self):
        self.assertEqual(tg.normalize_chat_id('https://t.me/channel_name/'), '@channel_name')
        self.assertEqual(tg.normalize_chat_id('-100123'), '-100123')
        for value in ('Private secret name', 'https://t.me/+secret', 'https://t.me/joinchat/secret'):
            with self.assertRaises(ValueError) as failure:
                tg.normalize_chat_id(value)
            self.assertNotIn(value, str(failure.exception))
        for desc in ('chat not found test-secret-token', 'arbitrary test-secret-token'):
            self.assertNotIn('test-secret-token', str(tg.TelegramAPIError('sendDocument', 400, description=desc)))

    def test_caption_and_buttons_keep_template_order(self):
        self.target()
        info, jar, url = tg.discover_documents(self.root)[0]
        self.assertEqual(tg.caption(info), '<b>AtomChat | CI Build #123</b>\nVersion: 26.3\nBranch: main\nCommit: <code>abcdef0</code>\n\nCommit message:\n<pre>Fix &lt;unsafe&gt; &amp; render</pre>')
        fields = tg.notification_fields(info, url)
        buttons = json.loads(fields['reply_markup'])['inline_keyboard'][0]
        self.assertEqual([b['text'] for b in buttons], ['下载 Actions 产物', '构建详情'])
        self.assertEqual([b['url'] for b in buttons], [url, info['runUrl']])
        self.assertEqual(fields['parse_mode'], 'HTML')
        info['projectName'] = 'Other project'
        self.assertIn('<b>Other project |', tg.caption(info))

    def test_caption_utf16_budget(self):
        self.target()
        info = tg.discover_documents(self.root)[0][0]
        for field in ('projectName', 'versionName', 'branch', 'runNumber', 'commitMessage'):
            info[field] = '😀<&' * 1000
        rendered = tg.caption(info)
        import html, re
        plain = html.unescape(re.sub('<[^>]+>', '', rendered))
        self.assertLessEqual(len(plain.encode('utf-16-le')) // 2, 1024)
        self.assertNotIn('\ufffd', rendered)

    def test_recursive_multi_target_and_missing_optional_fields(self):
        for target in ('1.21.1-fabric', '1.21.1-neoforge', '1.20.1-forge'):
            directory, info = self.target(target)
            info.pop('commitSubject')
            info.pop('runNumber')
            self.rewrite(directory, info)
        documents = tg.discover_documents(self.root)
        self.assertEqual(len(documents), 6)
        self.assertEqual(documents[0][0]['commitMessage'], '')

    def test_all_preflight_finishes_before_sending(self):
        self.target()
        directory, info = self.target('1.20.1-forge')
        (directory / info['artifacts'][0]['fileName']).write_bytes(b'corrupt')
        with patch.object(tg, 'api_call') as api, self.assertRaises(ValueError):
            tg.main(self.root)
        api.assert_not_called()

    def test_reject_unsafe_or_nonbundled_artifacts(self):
        directory, info = self.target()
        original = info['artifacts'][0]['fileName']
        for filename in ('../evil.jar', 'C:\\evil.jar', 'atomchat-slim.jar', 'atomchat-sources.jar', 'file.zip'):
            info['artifacts'][0]['fileName'] = filename
            self.rewrite(directory, info)
            with self.subTest(filename=filename), self.assertRaises(ValueError):
                tg.discover_documents(self.root)
        info['artifacts'][0]['fileName'] = original
        info['artifacts'][0]['sha256'] = '0'*64
        self.rewrite(directory, info)
        with self.assertRaises(ValueError):
            tg.discover_documents(self.root)

    def test_cloud_boundary_and_fallback(self):
        self.target()
        info, jar, url = tg.discover_documents(self.root)[0]
        for size, method in ((50_000_000, 'sendDocument'), (50_000_001, 'sendMessage')):
            with patch.object(Path, 'stat', return_value=MagicMock(st_size=size)), patch.object(tg, 'api_call') as api:
                tg.publish_cloud('test-token', info, jar, url)
                self.assertEqual(api.call_count, 1)
                self.assertEqual(api.call_args.args[1], method)
                self.assertEqual(api.call_args.kwargs['endpoint'], tg.CLOUD_API)
                if method == 'sendMessage':
                    self.assertIn('JAR 超过 50 MB', api.call_args.args[2]['text'])
                    self.assertIn('需登录 GitHub', api.call_args.args[2]['text'])
                    self.assertIn(f'<code>{jar.name}</code>', api.call_args.args[2]['text'])

    def test_oversize_filename_is_html_escaped(self):
        self.target()
        info, _, url = tg.discover_documents(self.root)[0]
        # Use a mocked Path because some platforms forbid angle brackets in filenames.
        jar = MagicMock()
        jar.name = 'AtomChat-<target>&linux.jar'
        jar.stat.return_value.st_size = 50_000_001
        with patch.object(tg, 'api_call') as api:
            tg.publish_cloud('token', info, jar, url)
        text = api.call_args.args[2]['text']
        self.assertTrue(text.startswith(tg.caption(info)))
        self.assertIn('<code>AtomChat-&lt;target&gt;&amp;linux.jar</code>', text)

    def test_throttle_waits_only_for_remaining_interval(self):
        throttle = tg.MessageThrottle()
        with patch.object(tg.time, 'monotonic', side_effect=[10.0, 10.1, 11.1, 13.0]):
            throttle.wait()
            self.sleep.assert_not_called()
            throttle.wait()
            self.sleep.assert_called_once()
            self.assertAlmostEqual(self.sleep.call_args.args[0], 1.0)
            # An upload taking longer than the interval requires no additional wait.
            throttle.wait()
            self.assertEqual(self.sleep.call_count, 1)

    def test_cloud_loop_paces_each_send(self):
        self.target()
        with patch.object(tg, 'publish_cloud') as cloud, patch.object(tg, 'MessageThrottle') as throttle:
            tg.main(self.root)
            self.assertEqual(cloud.call_count, 2)
            self.assertEqual(throttle.return_value.wait.call_count, 2)

    def test_local_loop_paces_each_send(self):
        self.target()
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(tg, 'local_server') as server, patch.object(tg, 'logout_cloud'), patch.object(tg, 'wait_ready'), patch.object(tg, 'api_call') as api, patch.object(tg, 'MessageThrottle') as throttle:
            server.return_value.__enter__.return_value = process
            tg.publish(Path('binary'), 'token', tg.discover_documents(self.root))
            self.assertEqual(throttle.return_value.wait.call_count, 2)
            self.assertEqual([c.args[1] for c in api.call_args_list], ['sendDocument', 'sendDocument', 'logOut'])

    def test_multipart_uses_jar_name_and_mime(self):
        jar = self.root / 'atomchat.jar'
        jar.write_bytes(b'jar payload')
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = io.BytesIO(b'{"ok": true, "result": {}}')
        with patch.object(tg.urllib.request, 'build_opener', return_value=opener):
            tg.api_call('test-token', 'sendDocument', {'caption': 'test'}, endpoint=tg.CLOUD_API, document=jar)
        request = opener.open.call_args.args[0]
        self.assertIn(b'filename="atomchat.jar"', request.data)
        self.assertIn(b'application/java-archive', request.data)
        self.assertNotIn(b'android', request.data)

    def test_timeout_is_sanitized_and_never_retried(self):
        opener = MagicMock()
        opener.open.side_effect = urllib.error.URLError('https://secret-token@private.example/')
        with patch.object(tg.urllib.request, 'build_opener', return_value=opener), self.assertRaises(tg.TelegramAPIError) as failure:
            tg.api_call('secret-token', 'sendDocument', endpoint=tg.CLOUD_API)
        self.assertEqual(opener.open.call_count, 1)
        self.assertNotIn('secret-token', str(failure.exception))

    def test_http_error_body_never_leaks_credentials(self):
        secret = 'test-secret-token-test-private-chat-api-hash'
        for description, hint in (('chat not found ' + secret, 'destination chat not found'),
                                  ('not enough rights ' + secret, 'bot cannot post'),
                                  ("can't parse entities " + secret, 'HTML caption'),
                                  (secret, 'check credentials')):
            payload = json.dumps({'ok': False, 'description': description}).encode()
            opener = MagicMock()
            opener.open.side_effect = urllib.error.HTTPError(
                'https://api.telegram.org/bot' + secret + '/sendDocument', 400,
                description, {}, io.BytesIO(payload))
            with self.subTest(hint=hint), patch.object(tg.urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(tg.TelegramAPIError) as failure:
                    tg.api_call(secret, 'sendDocument', endpoint=tg.CLOUD_API)
                self.assertIn(hint, str(failure.exception))
                self.assertNotIn(secret, str(failure.exception))
                self.assertEqual(opener.open.call_count, 1)

    def test_malformed_api_responses_are_safe(self):
        for payload in (b'private-secret invalid json', b'[]',
                        b'{"ok":false,"description":"private-secret","parameters":"private-secret"}'):
            opener = MagicMock()
            opener.open.return_value.__enter__.return_value = io.BytesIO(payload)
            with self.subTest(payload=payload), patch.object(tg.urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(RuntimeError) as failure:
                    tg.api_call('private-secret', 'sendDocument', endpoint=tg.CLOUD_API)
                self.assertNotIn('private-secret', str(failure.exception))

    def test_invalid_chat_fails_before_any_request(self):
        self.target()
        with patch.dict(os.environ, {'TELEGRAM_CHAT_ID': 'private channel display name'}), patch.object(tg, 'api_call') as api:
            with self.assertRaises(ValueError):
                tg.main(self.root)
        api.assert_not_called()

    def test_all_dynamic_caption_fields_escape_html(self):
        self.target()
        info = tg.discover_documents(self.root)[0][0]
        for field in ('projectName', 'versionName', 'branch', 'runNumber', 'commitMessage', 'commit'):
            info[field] = '<i>&x</i>'
        text = tg.caption(info)
        self.assertNotIn('<i>', text)
        self.assertNotIn('</i>', text)
        self.assertIn('&lt;i&gt;&amp;x&lt;/i&gt;', text)

    def test_local_server_keeps_bot_credentials_out_of_process_and_cleans_state(self):
        process = MagicMock()
        process.poll.return_value = None
        with patch.dict(os.environ, {'RUNNER_TEMP': str(self.root), 'TELEGRAM_API_ID': '123',
                                     'TELEGRAM_API_HASH': 'a'*32}), patch.object(tg.subprocess, 'Popen', return_value=process) as launch:
            with tg.local_server(Path('telegram-bot-api')):
                args, kwargs = launch.call_args
                self.assertNotIn('test-secret-token', ' '.join(args[0]))
                self.assertNotIn('@test_channel', ' '.join(args[0]))
                self.assertNotIn('TELEGRAM_BOT_TOKEN', kwargs['env'])
                self.assertNotIn('TELEGRAM_CHAT_ID', kwargs['env'])
                self.assertEqual(kwargs['env']['TELEGRAM_API_ID'], '123')
                self.assertIn('--http-ip-address=127.0.0.1', args[0])
                self.assertEqual(kwargs['stdout'], tg.subprocess.DEVNULL)
                self.assertEqual(kwargs['stderr'], tg.subprocess.DEVNULL)
                state = kwargs['cwd']
                self.assertTrue(state.is_dir())
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=30)
            self.assertFalse(state.exists())

    def test_local_api_bypasses_environment_proxy(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value = io.BytesIO(b'{"ok":true,"result":{}}')
        with patch.object(tg.urllib.request, 'ProxyHandler') as proxy, patch.object(tg.urllib.request, 'build_opener', return_value=opener):
            tg.api_call('private-token', 'getMe')
            proxy.assert_called_once_with({})

    def test_local_limit_file_uri_and_no_duplicate_retry(self):
        self.target()
        documents = tg.discover_documents(self.root)
        info, jar, url = documents[0]
        with patch.object(Path, 'stat', return_value=MagicMock(st_size=2_000_000_000)):
            self.assertTrue(tg.request_payload(info, jar, url)['document'].startswith('file:'))
        with patch.object(Path, 'stat', return_value=MagicMock(st_size=2_000_000_001)), self.assertRaises(ValueError):
            tg.request_payload(info, jar, url)
        process = MagicMock()
        process.poll.return_value = None
        with patch.object(tg, 'local_server') as server, patch.object(tg, 'logout_cloud'), patch.object(tg, 'wait_ready'), patch.object(tg, 'api_call') as api:
            server.return_value.__enter__.return_value = process
            tg.publish(Path('binary'), 'token', documents)
            self.assertEqual([c.args[1] for c in api.call_args_list], ['sendDocument', 'sendDocument', 'logOut'])
        with patch.object(tg, 'local_server') as server, patch.object(tg, 'logout_cloud'), patch.object(tg, 'wait_ready'), patch.object(tg, 'api_call') as api:
            server.return_value.__enter__.return_value = process
            api.side_effect = [tg.TelegramAPIError('sendDocument', 0), None]
            with self.assertRaises(tg.TelegramAPIError):
                tg.publish(Path('binary'), 'token', documents)
            self.assertEqual([c.args[1] for c in api.call_args_list], ['sendDocument', 'logOut'])


if __name__ == '__main__':
    unittest.main()
