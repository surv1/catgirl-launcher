// Run with: npm test
const assert = require('assert');
const path = require('path');
const r = require('../src/main/rules');

let n = 0;
const t = (name, fn) => { fn(); n++; console.log('ok -', name); };

t('mavenPath', () => {
  assert.strictEqual(r.mavenPath('net.fabricmc:fabric-loader:0.16.9'), 'net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar');
  assert.strictEqual(r.mavenPath('org.lwjgl:lwjgl:3.3.3:natives-windows'), 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar');
  assert.strictEqual(r.mavenPath('a.b:c:1@zip'), 'a/b/c/1/c-1.zip');
});

t('rules: os allow/disallow', () => {
  const rules = [{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }];
  assert.strictEqual(r.rulesAllow(rules, {}, 'windows', 'x64'), true);
  assert.strictEqual(r.rulesAllow(rules, {}, 'osx', 'arm64'), false);
  assert.strictEqual(r.rulesAllow([{ action: 'allow', os: { name: 'linux' } }], {}, 'windows'), false);
  assert.strictEqual(r.rulesAllow(undefined), true);
});

t('rules: features', () => {
  const rule = [{ action: 'allow', features: { is_quick_play_multiplayer: true } }];
  assert.strictEqual(r.rulesAllow(rule, { is_quick_play_multiplayer: true }), true);
  assert.strictEqual(r.rulesAllow(rule, {}), false);
});

t('expandArgs + quick play', () => {
  const list = ['--username', '${auth_player_name}', { rules: [{ action: 'allow', features: { is_quick_play_multiplayer: true } }], value: ['--quickPlayMultiplayer', '${quickPlayMultiplayer}'] }, { rules: [{ action: 'allow', features: { is_demo_user: true } }], value: '--demo' }];
  const out = r.expandArgs(list, { auth_player_name: 'Jerrix', quickPlayMultiplayer: 'playnexusmc.net' }, { is_quick_play_multiplayer: true });
  assert.deepStrictEqual(out, ['--username', 'Jerrix', '--quickPlayMultiplayer', 'playnexusmc.net']);
});

t('mergeVersions: fabric on vanilla', () => {
  const vanilla = {
    id: '1.21.1', mainClass: 'net.minecraft.client.main.Main', type: 'release',
    assetIndex: { id: '17' }, downloads: { client: {} }, javaVersion: { majorVersion: 21 },
    libraries: [{ name: 'org.ow2.asm:asm:9.3' }, { name: 'com.mojang:brigadier:1.0.18' }],
    arguments: { game: ['--a'], jvm: ['-cp', '${classpath}'] },
  };
  const fabric = {
    id: 'fabric-loader-0.16.9-1.21.1', inheritsFrom: '1.21.1', mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient',
    libraries: [{ name: 'org.ow2.asm:asm:9.7.1', url: 'https://maven.fabricmc.net/' }, { name: 'net.fabricmc:fabric-loader:0.16.9', url: 'https://maven.fabricmc.net/' }],
    arguments: { game: [], jvm: ['-DFabricMcEmu= net.minecraft.client.main.Main '] },
  };
  const m = r.mergeVersions(vanilla, fabric);
  assert.strictEqual(m.mainClass, fabric.mainClass);
  assert.strictEqual(m.javaVersion.majorVersion, 21);
  assert.strictEqual(m.assetIndex.id, '17');
  const names = m.libraries.map((l) => l.name);
  assert.ok(names.includes('org.ow2.asm:asm:9.7.1'));
  assert.ok(!names.includes('org.ow2.asm:asm:9.3'));
  assert.ok(names.includes('com.mojang:brigadier:1.0.18'));
  assert.deepStrictEqual(m.arguments.jvm, ['-cp', '${classpath}', '-DFabricMcEmu= net.minecraft.client.main.Main ']);
  assert.ok(!('inheritsFrom' in m));
});

t('mergeVersions keeps same-name libs that only differ by rules in parent', () => {
  const parent = { libraries: [{ name: 'x:y:1', rules: [{ action: 'allow', os: { name: 'osx' } }] }, { name: 'x:y:2', rules: [{ action: 'disallow', os: { name: 'osx' } }] }] };
  const m = r.mergeVersions(parent, { id: 'c', libraries: [] });
  assert.strictEqual(m.libraries.length, 2);
});

t('resolveLibrary: mojang artifact, fabric maven, old natives', () => {
  const lib = r.resolveLibrary({ name: 'com.mojang:brigadier:1.0.18', downloads: { artifact: { path: 'com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar', url: 'https://libraries.minecraft.net/x.jar', sha1: 'abc', size: 1 } } }, '/libs', 'windows', 'x64');
  assert.strictEqual(lib.artifact.path, path.join('/libs', 'com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar'));
  const fab = r.resolveLibrary({ name: 'net.fabricmc:fabric-loader:0.16.9', url: 'https://maven.fabricmc.net/' }, '/libs', 'windows');
  assert.strictEqual(fab.artifact.url, 'https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar');
  const nat = r.resolveLibrary({
    name: 'org.lwjgl.lwjgl:lwjgl-platform:2.9.4', natives: { windows: 'natives-windows-${arch}' },
    downloads: { classifiers: { 'natives-windows-64': { path: 'n.jar', url: 'https://x/n.jar', sha1: 's', size: 2 } } },
  }, '/libs', 'windows', 'x64');
  assert.strictEqual(nat.natives.url, 'https://x/n.jar');
  assert.strictEqual(nat.artifact, null);
  assert.strictEqual(r.resolveLibrary({ name: 'a:b:1', rules: [{ action: 'allow', os: { name: 'osx' } }] }, '/l', 'windows'), null);
});

t('legacy minecraftArguments', () => {
  const { jvm, game } = r.buildArgs({ minecraftArguments: '--username ${auth_player_name} --gameDir ${game_directory}' }, { auth_player_name: 'Jerrix', game_directory: '/g', natives_directory: '/n', classpath: 'a.jar' }, {});
  assert.deepStrictEqual(game, ['--username', 'Jerrix', '--gameDir', '/g']);
  assert.ok(jvm.includes('-Djava.library.path=/n'));
  assert.ok(jvm.includes('a.jar'));
});

t('versionAtLeast', () => {
  assert.ok(r.versionAtLeast('1.21.11', '1.20'));
  assert.ok(!r.versionAtLeast('1.19.4', '1.20'));
});

console.log(`\n${n} tests passed`);
