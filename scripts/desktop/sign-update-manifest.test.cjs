'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const {spawnSync}=require('node:child_process');const {preimage}=require('./sign-update-manifest.cjs');
const repo=path.resolve(__dirname,'../..'),corpus=path.join(repo,'desktop/src/test/resources/update-v1/release-corpus');
const java=path.join(repo,'.toolchain/jdk-21.0.12.1+1/bin/java.exe'),app=path.join(repo,'desktop/build/compose/binaries/main/app/Riff Desktop Preview');
const seed='9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60';
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'riff-signer-test-'));
 t.after(()=>{if(path.dirname(root)!==os.tmpdir()||!path.basename(root).startsWith('riff-signer-test-'))throw Error('Unsafe test cleanup');fs.rmSync(root,{recursive:true,force:true});});
 const key=path.join(root,'TEST-ONLY-key.der');fs.writeFileSync(key,Buffer.from('302e020100300506032b657004220420'+seed,'hex'),{mode:0o600});
 return {root,key};
}
function invoke(f,{name='valid',output=path.join(f.root,crypto.randomUUID()+'.json'),...overrides}={}){
 const flags={'--payload':path.join(corpus,name,'payload.bin'),'--key-id':'test-rfc8032-01','--private-key':f.key,'--output':output,'--java':java,'--validator-app':app,...overrides};
 const result=spawnSync(process.execPath,[path.join(__dirname,'sign-update-manifest.cjs'),...Object.entries(flags).flat()],{encoding:'utf8',windowsHide:true,timeout:20000});
 assert.equal(result.error,undefined);assert.ok(!((result.stdout||'')+(result.stderr||'')).includes(seed));return {...result,output:flags['--output']};
}
test('independent signer preimage matches immutable Module 2 anchor',()=>{
 const root=path.join(repo,'desktop/src/test/resources/update-v1');
 assert.deepEqual(preimage(fs.readFileSync(path.join(root,'payload.bin')),'test-rfc8032-01'),fs.readFileSync(path.join(root,'preimage.bin')));
 const inventory=JSON.parse(fs.readFileSync(path.join(root,'sha256.json')));
 for(const [file,hash] of Object.entries(inventory))assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex'),hash);
});
test('all 27 corpus payloads reuse Kotlin syntax validation and valid signatures reproduce',t=>{
 const f=fixture(t),lines=fs.readFileSync(path.join(corpus,'expectations.tsv'),'utf8').trim().split('\n');assert.equal(lines.length,27);
 for(const line of lines){const name=line.split('\t')[0],expected=JSON.parse(fs.readFileSync(path.join(corpus,name,'expected-results.json')));
    const currentSeed=expected.signedKeyId==='test-rfc8032-rotated'?crypto.createHash('sha256').update('RIFF RELEASE CORPUS SECOND PUBLIC TEST KEY - NOT FOR PRODUCTION').digest():Buffer.from(seed,'hex');
  fs.writeFileSync(f.key,Buffer.concat([Buffer.from('302e020100300506032b657004220420','hex'),currentSeed]));
  const result=invoke(f,{name,'--key-id':expected.signedKeyId});
  if(expected.syntaxError){assert.equal(result.status,3,name);assert.equal(fs.existsSync(result.output),false);assert.match(result.stderr,new RegExp(expected.syntaxError));}
  else{assert.equal(result.status,0,name);const envelope=JSON.parse(fs.readFileSync(result.output));
   assert.equal(Object.keys(envelope).sort().join(','),'algorithm,envelopeVersion,keyId,payloadBase64,signatureBase64');
   assert.deepEqual(Buffer.from(envelope.payloadBase64,'base64'),fs.readFileSync(path.join(corpus,name,'payload.bin')));
   assert.deepEqual(Buffer.from(envelope.signatureBase64,'base64'),fs.readFileSync(path.join(corpus,name,'signature.bin')));
  }
 }
});
test('two runs are identical; LF and whitespace changes alter signatures',t=>{
 const f=fixture(t),a=invoke(f),b=invoke(f),c=invoke(f,{name:'no-final-newline'}),d=invoke(f,{name:'whitespace'});
 for(const result of [a,b,c,d])assert.equal(result.status,0);
 assert.deepEqual(fs.readFileSync(a.output),fs.readFileSync(b.output));
 assert.deepEqual(fs.readFileSync(a.output),fs.readFileSync(path.join(corpus,'valid/envelope.json')));
 const signature=result=>JSON.parse(fs.readFileSync(result.output)).signatureBase64;
 assert.notEqual(signature(a),signature(c));assert.notEqual(signature(a),signature(d));
});
test('no-clobber keeps existing output bytes and leaves no owned temporary file',t=>{
 const f=fixture(t),output=path.join(f.root,'existing.json');fs.writeFileSync(output,'existing output');
 const result=invoke(f,{output});assert.equal(result.status,5);assert.match(result.stderr,/OUTPUT_EXISTS/);assert.equal(fs.readFileSync(output,'utf8'),'existing output');
 assert.equal(fs.readdirSync(f.root).some(name=>name.endsWith('.tmp')),false);
});
test('invalid or oversized keys and unsupported algorithms disclose no key material',t=>{
 const f=fixture(t);const sentinel='TEST_ONLY_SECRET_MUST_NOT_APPEAR';
 const wrongAlgorithm=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'}).privateKey.export({format:'der',type:'pkcs8'});
 for(const data of [Buffer.from(sentinel),Buffer.alloc(8193),wrongAlgorithm,Buffer.concat([Buffer.from('302e020100300506032b657004220420'+seed,'hex'),Buffer.from([0])])]){
  fs.writeFileSync(f.key,data);const result=invoke(f);assert.equal(result.status,4);assert.equal(fs.existsSync(result.output),false);assert.ok(!result.stderr.includes(sentinel));
 }
});
test('expected public key mismatch fails closed; correct public key succeeds',t=>{
 const f=fixture(t),wrong=path.join(f.root,'wrong-public.der');fs.writeFileSync(wrong,crypto.generateKeyPairSync('ed25519').publicKey.export({format:'der',type:'spki'}));
 const bad=invoke(f,{'--public-key':wrong});assert.equal(bad.status,4);assert.match(bad.stderr,/KEY_MISMATCH/);assert.equal(fs.existsSync(bad.output),false);
 assert.equal(invoke(f,{'--public-key':path.join(corpus,'public-key.der')}).status,0);
});
test('invalid payload is rejected before any private key read',t=>{
 const f=fixture(t),result=invoke(f,{name:'duplicate','--private-key':path.join(f.root,'MISSING-TEST-KEY')});
 assert.equal(result.status,3);assert.match(result.stderr,/INVALID_PAYLOAD/);assert.equal(fs.existsSync(result.output),false);
});
test('bad input paths, validator paths and publication failures create no output',t=>{
 const f=fixture(t);
 for(const [overrides,status] of [[{'--payload':f.root},3],[{'--payload':path.join(f.root,'missing')},3],[{'--private-key':path.join(f.root,'missing')},4],
  [{'--java':path.join(f.root,'missing')},6],[{'--validator-app':f.root},6],[{'--output':path.join(f.root,'missing','out.json')},5]]){
  const result=invoke(f,overrides);assert.equal(result.status,status);assert.equal(fs.existsSync(result.output),false);
 }
 assert.equal(fs.readdirSync(f.root).some(name=>name.endsWith('.tmp')),false);
});
test('CLI rejects missing/duplicate/unknown arguments and malformed key ID',t=>{
 const f=fixture(t);const result=invoke(f,{'--key-id':'Bad Key'});assert.equal(result.status,2);assert.equal(fs.existsSync(result.output),false);
 const raw=args=>spawnSync(process.execPath,[path.join(__dirname,'sign-update-manifest.cjs'),...args],{encoding:'utf8',windowsHide:true});
 for(const args of [[],['--unknown','x'],['--payload','a','--payload','b']])assert.equal(raw(args).status,2);
});
test('hard-link publication failure leaves no partial destination or temporary file',t=>{
 const f=fixture(t);const {run}=require('./sign-update-manifest.cjs');const original=fs.linkSync;
 fs.linkSync=()=>{const error=new Error('simulated local publication failure');error.code='EPERM';throw error;};
 const output=path.join(f.root,'never-published.json');
 try{assert.throws(()=>run(['--payload',path.join(corpus,'valid/payload.bin'),'--key-id','test-rfc8032-01','--private-key',f.key,'--output',output,'--java',java,'--validator-app',app]),/OUTPUT_ERROR/);}
 finally{fs.linkSync=original;}
 assert.equal(fs.existsSync(output),false);assert.equal(fs.readdirSync(f.root).some(name=>name.endsWith('.tmp')),false);
});
test('atomic publication refuses a destination created after the initial existence check',t=>{
 const f=fixture(t),{run}=require('./sign-update-manifest.cjs'),original=fs.linkSync,output=path.join(f.root,'race.json');
 fs.linkSync=(temp,dest)=>{fs.writeFileSync(dest,'concurrent output');return original(temp,dest);};
 try{assert.throws(()=>run(['--payload',path.join(corpus,'valid/payload.bin'),'--key-id','test-rfc8032-01','--private-key',f.key,'--output',output,'--java',java,'--validator-app',app]),/OUTPUT_EXISTS/);}
 finally{fs.linkSync=original;}
 assert.equal(fs.readFileSync(output,'utf8'),'concurrent output');assert.equal(fs.readdirSync(f.root).some(name=>name.endsWith('.tmp')),false);
});
test('partial temporary writes and flush failures never publish usable output',t=>{
 const f=fixture(t),{run}=require('./sign-update-manifest.cjs');
 for(const phase of ['write','flush']){
  const originalWrite=fs.writeFileSync,originalSync=fs.fsyncSync,output=path.join(f.root,phase+'.json');
  if(phase==='write')fs.writeFileSync=(fd,data,...rest)=>{if(typeof fd==='number'){originalWrite(fd,data.subarray(0,5),...rest);throw Error('TEST write interruption');}return originalWrite(fd,data,...rest);};
  else fs.fsyncSync=()=>{throw Error('TEST force failure');};
  try{assert.throws(()=>run(['--payload',path.join(corpus,'valid/payload.bin'),'--key-id','test-rfc8032-01','--private-key',f.key,'--output',output,'--java',java,'--validator-app',app]),/OUTPUT_ERROR/);}
  finally{fs.writeFileSync=originalWrite;fs.fsyncSync=originalSync;}
  assert.equal(fs.existsSync(output),false);assert.equal(fs.readdirSync(f.root).some(name=>name.endsWith('.tmp')),false);
 }
});