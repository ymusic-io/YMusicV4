#!/usr/bin/env node
// Builds the update part of the server's App config (`releases`, `engineJs`, `playerJs` — shape:
// ymusic-server src/appConfig.ts) for the files of one GitHub release, ready to paste into the dashboard.
//
//   node update-config.mjs <release dir> <owner/repo> <tag> <YMusicV4 checkout> [notes dir]
//
// The notes dir holds one update-dialog text per platform family: notes-android.txt, notes-macos.txt,
// notes-windows.txt (a missing file = no notes for that family).
//
// Urls are the release's asset download links, live once the draft is published. Signatures use
// bundle.signing.private.key (JS bundles) and update.private.key (installers) from the checkout's
// secrets.properties (./decrypt.sh), each checked against its .public.key. A missing key fails the
// run: an entry with an empty signature is one the apps refuse, so it must never reach the dashboard.

import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const [dir, repo, tag, root, notesDir] = process.argv.slice(2)
if (!dir || !repo || !tag || !root) {
  console.error('usage: update-config.mjs <release dir> <owner/repo> <tag> <YMusicV4 checkout> [notes dir]')
  process.exit(2)
}

const read = (path) => readFileSync(join(root, path), 'utf8')
const match = (path, regex, what) => {
  const found = read(path).match(regex)
  if (!found) {
    console.error(`${what} not found in ${path}`)
    process.exit(1)
  }
  return found[1]
}

const versionName = match('gradle.properties', /^ymusic\.versionName=(.+)$/m, 'ymusic.versionName').trim()
const androidVersionCode = Number(match('androidApp/build.gradle.kts', /^\s*versionCode = (\d+)/m, 'versionCode'))
const desktopVersionCode = Number(match('desktopApp/build.gradle.kts', /^val desktopVersionCode = (\d+)/m, 'desktopVersionCode'))
const engineVersion = Number(match('buildSrc/src/main/kotlin/Dependencies.kt', /const val engineJsVersion = (\d+)L/, 'engineJsVersion'))
const playerVersion = Number(match('buildSrc/src/main/kotlin/Dependencies.kt', /const val playerJsVersion = (\d+)L/, 'playerJsVersion'))

const notesFor = (platform) => {
  const path = notesDir ? join(notesDir, `notes-${platform.split('-')[0]}.txt`) : ''
  return path && existsSync(path) ? readFileSync(path, 'utf8').trim() : ''
}
const changelogUrl = `https://github.com/${repo}/blob/${tag}/CHANGELOG.md`
const assetUrl = (file) => `https://github.com/${repo}/releases/download/${tag}/${file}`

const secretsPath = join(root, 'secrets.properties')
const secrets = existsSync(secretsPath)
  ? Object.fromEntries(
      readFileSync(secretsPath, 'utf8')
        .split('\n')
        .map((line) => line.match(/^([\w.]+)=(.*)$/))
        .filter(Boolean)
        .map((found) => [found[1], found[2].trim()]),
    )
  : {}

const signer = (keyName) => {
  const privateBase64 = secrets[`${keyName}.private.key`]
  if (!privateBase64) {
    console.error(`${keyName}.private.key is not in secrets.properties`)
    return null
  }
  const privateKey = createPrivateKey({ key: Buffer.from(privateBase64, 'base64'), format: 'der', type: 'pkcs8' })
  const publicKey = createPublicKey(privateKey)
  if (publicKey.export({ format: 'der', type: 'spki' }).toString('base64') !== secrets[`${keyName}.public.key`]) {
    console.error(`${keyName}.private.key does not match ${keyName}.public.key, the key the apps pin`)
    process.exit(1)
  }
  return (text) => {
    const message = Buffer.from(text, 'utf8')
    const signature = sign('sha256', message, privateKey)
    if (!verify('sha256', message, publicKey, signature)) {
      console.error(`signature over "${text}" failed its own verification`)
      process.exit(1)
    }
    return signature.toString('base64')
  }
}
const signUpdate = signer('update')
const signBundle = signer('bundle.signing')
if (!signUpdate || !signBundle) process.exit(1)

const sha256Of = async (path) => {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(path)) hash.update(chunk)
  return hash.digest('hex')
}

const INSTALLERS = [
  { platform: 'android-armeabi-v7a', file: 'android-armv7.apk', versionCode: androidVersionCode },
  { platform: 'android-arm64-v8a', file: 'android-arm64.apk', versionCode: androidVersionCode },
  { platform: 'android-x86', file: 'android-x86.apk', versionCode: androidVersionCode },
  { platform: 'android-x86_64', file: 'android-x86_64.apk', versionCode: androidVersionCode },
  { platform: 'macos-arm64', file: 'macos-arm64.dmg', versionCode: desktopVersionCode },
  { platform: 'macos-x64', file: 'macos-intel.dmg', versionCode: desktopVersionCode },
  { platform: 'windows-x64', file: 'windows-x64-setup.exe', versionCode: desktopVersionCode },
  { platform: 'windows-arm64', file: 'windows-arm64-setup.exe', versionCode: desktopVersionCode },
]

const releases = {}
for (const { platform, file, versionCode } of INSTALLERS) {
  const path = join(dir, file)
  const sha256 = await sha256Of(path)
  releases[platform] = {
    versionCode,
    versionName,
    notes: notesFor(platform),
    url: assetUrl(file),
    size: statSync(path).size,
    sha256,
    signature: signUpdate(`app-${platform}:${versionCode}:${sha256}`),
    changelogUrl,
  }
}

const bundle = async (name, version) => {
  const file = `${name}.${version}.js`
  const sha256 = await sha256Of(join(dir, file))
  return { version, url: assetUrl(file), signature: signBundle(`${name}:${version}:${sha256}`) }
}

const config = {
  releases,
  engineJs: await bundle('engine', engineVersion),
  playerJs: await bundle('player', playerVersion),
}
console.log(JSON.stringify(config, null, 2))
