"""Install a project-local build runtime from verified official distributions.

Reuses this computer's existing Android SDK licence acceptances; never accepts
new licence terms or changes the system SDK, PATH, or Java installation.
"""
from pathlib import Path
import hashlib
import shutil
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tooling'
TOOLS.mkdir(exist_ok=True)
SDK = TOOLS / 'android-sdk'
SDK.mkdir(exist_ok=True)
EXISTING_SDK = Path.home() / 'AppData/Local/Android/Sdk'

def download(url, dest):
    if not dest.exists():
        print('Downloading', dest.name, flush=True)
        with urllib.request.urlopen(url, timeout=120) as src, dest.open('wb') as out:
            shutil.copyfileobj(src, out)

def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError('Unsafe archive path')
        z.extractall(destination)

gradle = TOOLS / 'gradle-8.11.1'
if not (gradle / 'bin/gradle.bat').exists():
    archive = TOOLS / 'gradle-8.11.1-bin.zip'
    url = 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    download(url, archive)
    expected = urllib.request.urlopen(url + '.sha256', timeout=30).read().decode().strip()
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        raise ValueError('Gradle checksum mismatch')
    extract(archive, TOOLS)

if not (EXISTING_SDK / 'licenses/android-sdk-license').exists():
    raise SystemExit('Android SDK licence acceptance is required in Android Studio before continuing.')
shutil.copytree(EXISTING_SDK / 'licenses', SDK / 'licenses', dirs_exist_ok=True)

repo_url = 'https://dl.google.com/android/repository/'
repository = ET.fromstring(urllib.request.urlopen(repo_url + 'repository2-1.xml', timeout=60).read())
for package_name, relative in [('platforms;android-35', 'platforms/android-35'), ('build-tools;35.0.0', 'build-tools/35.0.0')]:
    destination = SDK / relative
    if destination.exists():
        continue
    package = next(p for p in repository if p.tag.endswith('remotePackage') and p.attrib.get('path') == package_name)
    archive_node = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in (None, 'windows'))
    complete = archive_node.find('complete')
    filename = complete.findtext('url')
    archive = TOOLS / filename
    download(repo_url + filename, archive)
    checksum = complete.find('checksum')
    checksum_kind = checksum.attrib.get('type', 'sha1')
    if hashlib.new(checksum_kind, archive.read_bytes()).hexdigest() != checksum.text.strip():
        raise ValueError('Android SDK checksum mismatch')
    temp = TOOLS / ('unpack-' + relative.replace('/', '-'))
    extract(archive, temp)
    roots = list(temp.iterdir())
    if len(roots) != 1:
        raise ValueError('Unexpected Android SDK archive structure')
    # Some Google archive aliases contain an SDK extension rather than the base
    # package. Respect the package's own identity; Gradle can fetch a missing base
    # platform using the existing licence acceptance.
    package_xml = roots[0] / 'package.xml'
    if package_xml.exists():
        installed = ET.parse(package_xml).getroot()
        local = next((n for n in installed if n.tag.endswith('localPackage')), None)
        if local is not None and local.attrib.get('path'):
            destination = SDK / local.attrib['path'].replace(';', '/')
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        shutil.move(str(roots[0]), str(destination))
    print('Installed', destination.relative_to(SDK), flush=True)

(ROOT / 'local.properties').write_text('sdk.dir=' + str(SDK).replace('\\', '/') + '\n')
print('Project-local Android build tools are ready.', flush=True)
