#!/usr/bin/env bash
# Verify already-built APKs, then stage delivery files. This never builds/signs.
# Usage: BUILD_LOG=/path/to/successful-build.log ./scripts/verify-apk.sh
#        ./scripts/verify-apk.sh --checksums-only  # after adding source/Git ZIPs
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export EASYBASE64_PROJECT_DIR="$project_dir"
python3 - "$@" <<'PY'
import datetime, hashlib, json, os, re, shutil, ssl, subprocess, sys, tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

root = Path(os.environ['EASYBASE64_PROJECT_DIR'])
delivery = Path(os.environ.get('EASYBASE64_DELIVERY_DIR', root.parent / 'deliverables'))
verification = root / 'docs/verification'
version = os.environ.get('EXPECTED_VERSION_NAME', '1.1.0')
package = 'com.hycnyl.easybase64'
expected_abis = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}

def require(condition, message):
    if not condition:
        raise SystemExit('Verification failed: ' + message)

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def checksums():
    require(delivery.is_dir(), 'delivery directory is missing')
    files = sorted(p for p in delivery.rglob('*') if p.is_file() and p.name != 'SHA256SUMS.txt')
    lines = [f'{digest(p)}  {p.relative_to(delivery).as_posix()}\n' for p in files]
    (delivery / 'SHA256SUMS.txt').write_text(''.join(lines), encoding='utf-8')
    print(f'SHA-256 manifest updated for {len(files)} delivery files.')

if sys.argv[1:] == ['--checksums-only']:
    checksums()
    raise SystemExit(0)
require(not sys.argv[1:], 'supported argument: --checksums-only')

sdk_setting = re.search(r'^sdk\.dir=(.+)$', (root / 'local.properties').read_text(), re.M) if (root / 'local.properties').exists() else None
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or (sdk_setting.group(1) if sdk_setting else ''))
build_tools = sdk / 'build-tools/37.0.0'
for executable in ('apksigner', 'zipalign', 'aapt2'):
    require((build_tools / executable).is_file(), f'Android Build Tools 37.0.0 missing: {executable}; source the toolchain env.sh')

def run(executable, *args):
    result = subprocess.run([str(build_tools / executable), *map(str, args)], text=True, capture_output=True)
    require(result.returncode == 0, f'{executable} returned {result.returncode}; no files staged')
    return result.stdout + result.stderr

build_log = Path(os.environ.get('BUILD_LOG', root.parent / 'toolchains/final-build.log'))
require(build_log.is_file(), 'set BUILD_LOG to the completed aggregate build log')
require('BUILD SUCCESSFUL' in build_log.read_text(errors='replace'), 'aggregate build log does not contain BUILD SUCCESSFUL')
certificate = root / 'signing/easybase64-certificate.pem'
keystore = root / 'signing/easybase64-delivery.jks'
private_notes = root / 'signing/SIGNING-PRIVATE.txt'
for path in (certificate, keystore, private_notes, root / 'signing.properties'):
    require(path.is_file(), f'required signing delivery file missing: {path.name}')
certificate_digest = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()

source_files = []
for module in ('app', 'data', 'core'):
    source_files.extend(p for p in (root / module / 'src/main').rglob('*') if p.is_file())
    source_files.extend((root / module).glob('*.gradle.kts'))
    source_files.extend((root / module).glob('proguard*.pro'))
source_files.extend(root.glob('*.gradle.kts'))
latest_source = max((p.stat().st_mtime for p in source_files), default=0)

reports = {}
require(shutil.which('readelf') is not None, 'readelf is required (install binutils) for native 16 KiB verification')
for variant in ('release', 'debug'):
    directory = root / f'app/build/outputs/apk/{variant}'
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    elements = metadata['elements']
    require(len(elements) == 1 and not elements[0].get('filters'), f'{variant} output is split or filtered')
    require(metadata['applicationId'] == package, f'{variant} package mismatch')
    require(elements[0]['versionCode'] == 3 and elements[0]['versionName'] == version, f'{variant} version mismatch')
    apk = directory / elements[0]['outputFile']
    require(apk.is_file() and apk.stat().st_mtime >= latest_source, f'{variant} APK is older than main sources/configuration; rebuild first')
    badging = run('aapt2', 'dump', 'badging', apk)
    require(re.search(r"package: name='" + re.escape(package) + r"'.*versionCode='3'.*versionName='" + re.escape(version) + "'", badging), f'{variant} manifest identity mismatch')
    require(re.search(r"^(?:minSdkVersion|sdkVersion):'31'$", badging, re.M) and "targetSdkVersion:'36'" in badging, f'{variant} SDK bounds mismatch')
    require('android.permission.INTERNET' not in badging, f'{variant} unexpectedly requests INTERNET')
    require('android.permission.MANAGE_EXTERNAL_STORAGE' not in badging, f'{variant} unexpectedly requests broad storage access')
    require(not re.search(r"package:.*\bsplit='", badging), f'{variant} manifest is an APK split')
    with ZipFile(apk) as archive:
        native = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        abis = {n.split('/')[1] for n in native}
        require(not abis or expected_abis.issubset(abis), f'{variant} native libraries omit a universal ABI')
        for library in {n.rsplit('/', 1)[1] for n in native}:
            require(all(f'lib/{abi}/{library}' in native for abi in expected_abis), f'{variant} has uneven native ABI coverage: {library}')
        elf_evidence = []
        with tempfile.TemporaryDirectory(prefix='easybase64-elf-') as temporary_dir:
            for index, name in enumerate(sorted(native)):
                extracted = Path(temporary_dir) / f'{index}.so'
                with archive.open(name) as source, extracted.open('wb') as output:
                    shutil.copyfileobj(source, output, 65536)
                result = subprocess.run(['readelf', '-lW', str(extracted)], text=True, capture_output=True)
                require(result.returncode == 0, f'{variant} unreadable ELF: {name}')
                loads = [line.split() for line in result.stdout.splitlines() if line.strip().startswith('LOAD ')]
                require(bool(loads), f'{variant} ELF has no PT_LOAD segments: {name}')
                for fields in loads:
                    require(int(fields[-1], 16) >= 16384, f'{variant} ELF PT_LOAD alignment is below 16 KiB: {name}')
                    require(int(fields[1], 16) % 16384 == int(fields[2], 16) % 16384, f'{variant} ELF offset/address are incongruent for 16 KiB pages: {name}')
                elf_evidence.append(f'{name}\nSHA-256: {digest(extracted)}\n{result.stdout}\n')
    signature = run('apksigner', 'verify', '--verbose', '--print-certs', apk)
    require('Verifies' in signature, f'{variant} signature verification did not succeed')
    require('Verified using v2 scheme (APK Signature Scheme v2): true' in signature, f'{variant} lacks a verified APK v2 signature')
    if variant == 'release':
        require(certificate_digest in signature.lower(), 'release signer differs from delivered public certificate')
        require('application-debuggable' not in badging, 'release APK is debuggable')
    alignment = run('zipalign', '-c', '-P', '16', '-v', '4', apk)
    reports[variant] = dict(apk=apk, badging=badging, signature=signature, alignment=alignment, elf=''.join(elf_evidence) or 'No native libraries.\n', abis=sorted(abis))

# Validate actual report files; do not infer unexecuted Android tests as passing.
test_files = []
test_counts = {}
for module, variant in (('core', 'test'), ('data', 'testDebugUnitTest'), ('app', 'testDebugUnitTest')):
    files = sorted((root / module / 'build/test-results' / variant).glob('TEST-*.xml'))
    count = 0
    for path in files:
        suite = ET.parse(path).getroot()
        require(int(suite.get('failures', '0')) == 0 and int(suite.get('errors', '0')) == 0, f'{module} JVM test report has failures')
        count += int(suite.get('tests', '0'))
        test_files.append((path, Path('unit-tests') / module / path.name))
    require(module == 'app' or count > 0, f'no executed {module} JVM test report found')
    test_counts[module] = count

lint_files = []
for module in ('app', 'data'):
    directory = root / module / 'build/reports'
    xml = directory / 'lint-results-debug.xml'
    require(xml.is_file(), f'{module} Lint report is missing')
    require(not any(issue.get('severity') in ('Error', 'Fatal') for issue in ET.parse(xml).getroot().findall('issue')), f'{module} Lint contains errors')
    lint_files.extend((p, Path('lint') / f'{module}-{p.name}') for p in directory.glob('lint-results-debug.*'))

mapping = root / 'app/build/outputs/mapping/release/mapping.txt'
require(mapping.is_file(), 'release R8 mapping is missing')
delivery.mkdir(parents=True, exist_ok=True)
verification.mkdir(parents=True, exist_ok=True)

def copy(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)

for variant, report in reports.items():
    name = f'EasyBase64-{version}-' + ('debug-universal.apk' if variant == 'debug' else 'universal.apk')
    target = delivery / 'debug' / name if variant == 'debug' else delivery / name
    copy(report['apk'], target)
    prefix = 'apk' if variant == 'release' else 'debug-apk'
    for field, suffix in (('badging', 'manifest'), ('signature', 'signature'), ('alignment', 'alignment'), ('elf', 'elf-alignment')):
        (verification / f'{prefix}-{suffix}.txt').write_text(report[field], encoding='utf-8')

info = {
    'filename': f'EasyBase64-{version}-universal.apk',
    'bytes': reports['release']['apk'].stat().st_size,
    'sha256': digest(reports['release']['apk']),
    'native_abis': reports['release']['abis'],
    'min_sdk': 31, 'target_sdk': 36, 'compile_sdk': '37.2',
    'app_id': package, 'version_name': version, 'version_code': 1,
    'certificate_sha256': certificate_digest,
    'native_16k_page_alignment_verified': True,
    'verified_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'jvm_tests': test_counts,
    'developers': ['Hycnyl'], 'languages': ['zh-CN', 'en'],
}
(verification / 'apk-info.json').write_text(json.dumps(info, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
copy(build_log, verification / 'build.log')
copy(mapping, verification / 'release-mapping/mapping.txt')
for source, relative in test_files + lint_files:
    copy(source, verification / relative)
# Keep convenient historic filenames refreshed as well as complete report sets.
for module in ('core', 'data', 'app'):
    matches = [p for p, relative in test_files if relative.parts[1] == module]
    if len(matches) == 1:
        copy(matches[0], verification / f'{module}-unit-tests.xml')
    lint = root / module / 'build/reports/lint-results-debug.txt'
    if lint.is_file():
        copy(lint, verification / f'{module}-lint.txt')

copy(certificate, delivery / certificate.name)
copy(verification / 'apk-signature.txt', delivery / 'APK-SIGNATURE.txt')
signing_zip = delivery / 'EasyBase64-signing-private.zip'
with tempfile.NamedTemporaryFile(prefix='.signing-', suffix='.zip', dir=delivery, delete=False) as temporary:
    temporary_name = Path(temporary.name)
try:
    with ZipFile(temporary_name, 'w', ZIP_DEFLATED) as archive:
        for path in (keystore, certificate, private_notes):
            archive.write(path, path.relative_to(root).as_posix())
        archive.write(root / 'signing.properties', 'signing.properties')
        archive.write(verification / 'apk-signature.txt', 'APK-SIGNATURE.txt')
    temporary_name.chmod(0o600)
    temporary_name.replace(signing_zip)
finally:
    if temporary_name.exists():
        temporary_name.unlink()
shutil.copytree(verification, delivery / 'verification', dirs_exist_ok=True)
checksums()
print(f'Verified and staged EasyBase64 {version}: universal release/debug APKs, signing files, R8 mapping, JVM/Lint/build evidence.')
print('Refresh SHA256SUMS.txt with --checksums-only after adding final source/Git archives.')
PY
