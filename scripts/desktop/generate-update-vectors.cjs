'use strict';
// TEST-ONLY public RFC 8032 seed. Generates only the distinctly named release-corpus subtree.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../desktop/src/test/resources/update-v1/release-corpus');
const seed=Buffer.from('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60','hex');
const key=crypto.createPrivateKey({key:Buffer.concat([Buffer.from('302e020100300506032b657004220420','hex'),seed]),format:'der',type:'pkcs8'});
const publicKey=crypto.createPublicKey(key),pub=publicKey.export({format:'der',type:'spki'});
// A second distinct TEST-ONLY key, deterministically derived from this public non-secret label.
const rotatedSeed=crypto.createHash('sha256').update('RIFF RELEASE CORPUS SECOND PUBLIC TEST KEY - NOT FOR PRODUCTION').digest();
const rotatedKey=crypto.createPrivateKey({key:Buffer.concat([Buffer.from('302e020100300506032b657004220420','hex'),rotatedSeed]),format:'der',type:'pkcs8'});
const rotatedPublicKey=crypto.createPublicKey(rotatedKey),rotatedPub=rotatedPublicKey.export({format:'der',type:'spki'});
const base={schemaVersion:1,appId:'com.musicglass.desktop',channel:'preview',version:'0.2.0',releaseSequence:20,
 publishedAt:1793577600,expiresAt:1794182400,target:'windows-x64',minWindowsBuild:22000,minInstalledVersion:'0.1.0',
 workerProtocolMajor:1,databaseSchemaVersion:1,releaseNotes:'TEST ONLY — संगीत الموسيقى 😀',
 artifacts:[{type:'msi',os:'windows',arch:'x64',url:'https://releases.example.invalid/desktop/test.msi?opaque=keep%20this',sizeBytes:123456,sha256:'0'.repeat(64)}]};
const encoded=value=>Buffer.from(JSON.stringify(value)+'\n');
const changed=patch=>encoded({...base,...patch});
const plain=encoded(base),id='test-rfc8032-01',rotated='test-rfc8032-rotated';
const cases=[];
function add(name,payload,expected='ELIGIBLE',options={}){cases.push({name,payload,expected,...options});}
add('valid',plain);add('whitespace',Buffer.from(JSON.stringify(base,null,2)+'\n'));add('no-final-newline',plain.subarray(0,-1));
add('duplicate',Buffer.from(plain.toString().replace('{','{"schemaVersion":1,')),'INVALID_PAYLOAD');
add('invalid-utf8',Buffer.from([0xc3,0x28]),'INVALID_PAYLOAD');add('bom',Buffer.concat([Buffer.from([0xef,0xbb,0xbf]),plain]),'INVALID_PAYLOAD');
add('surrogate',changed({releaseNotes:'\ud800'}),'INVALID_PAYLOAD');add('future-schema',encoded({schemaVersion:2,future:true}),'UNSUPPORTED_FORMAT');
add('wrong-target',changed({appId:'other.product'}),'WRONG_TARGET');
add('wrong-host',changed({artifacts:[{...base.artifacts[0],url:'https://elsewhere.example.invalid/a'}]}),'WRONG_TARGET');
add('expired',changed({expiresAt:1793578000}),'EXPIRED');add('not-yet-valid',changed({publishedAt:1793578301}),'NOT_YET_VALID');
add('stale',changed({releaseSequence:9}),'STALE_RELEASE');
add('conflict',changed({releaseNotes:'Changed at the same sequence'}),'RELEASE_CONFLICT',{stored:'valid'});
add('rollback',changed({releaseSequence:21,version:'0.0.9',minInstalledVersion:'0.0.1'}),'ROLLBACK_REJECTED');
add('incompatible',changed({workerProtocolMajor:2}),'INCOMPATIBLE');
add('key-overlap',plain,'ELIGIBLE',{id:rotated});
add('key-id-tamper',plain,'INVALID_SIGNATURE',{outerId:rotated});
add('signature-tamper',plain,'INVALID_SIGNATURE',{tamperSignature:true});
add('payload-tamper',plain,'INVALID_SIGNATURE',{outerPayload:Buffer.concat([plain,Buffer.from(' ')])});
add('body-exact',Buffer.concat([plain,Buffer.alloc(65536-plain.length,32)]));
add('body-over',Buffer.concat([plain,Buffer.alloc(65537-plain.length,32)]),'SIZE_LIMIT');
add('notes-exact',changed({releaseNotes:'😀'.repeat(4096)}));add('notes-over',changed({releaseNotes:'😀'.repeat(4097)}),'SIZE_LIMIT');
add('number-over',Buffer.from(plain.toString().replace('"releaseSequence":20','"releaseSequence":12345678901234567')),'SIZE_LIMIT');
add('depth-over',encoded({...base,extra:[[{x:1}]]}),'SIZE_LIMIT');
add('bad-url',changed({artifacts:[{...base.artifacts[0],url:'https://releases.example.invalid/%2e%2e/a'}]}),'INVALID_PAYLOAD');
const inventory={};
function write(name,bytes){const dest=path.join(root,name);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,bytes);inventory[name]=crypto.createHash('sha256').update(bytes).digest('hex');}
write('rotated-public-key.der',rotatedPub);write('rotated-public-key.hex',Buffer.from(rotatedPub.subarray(12).toString('hex')+'\n'));
write('public-key.der',pub);write('public-key.hex',Buffer.from(pub.subarray(12).toString('hex')+'\n'));
const expectations=[];
for(const c of cases){
 const keyId=c.id||id,algorithm=Buffer.from('Ed25519'),keyBytes=Buffer.from(keyId),version=Buffer.alloc(4),keyLength=Buffer.alloc(2),algLength=Buffer.alloc(2),payloadLength=Buffer.alloc(4);
 version.writeUInt32BE(1);keyLength.writeUInt16BE(keyBytes.length);algLength.writeUInt16BE(algorithm.length);payloadLength.writeUInt32BE(c.payload.length);
 const preimage=Buffer.concat([Buffer.from('RIFF-DESKTOP-UPDATE\0'),version,keyLength,keyBytes,algLength,algorithm,payloadLength,c.payload]);
 const signingKey=keyId===rotated?rotatedKey:key,verificationKey=keyId===rotated?rotatedPublicKey:publicKey;
 const signature=crypto.sign(null,preimage,signingKey);
 if(!crypto.verify(null,preimage,verificationKey,signature))throw Error('TEST vector OpenSSL verification failed');
 const emittedSignature=Buffer.from(signature);if(c.tamperSignature)emittedSignature[0]^=1;
 const envelope={envelopeVersion:1,keyId:c.outerId||keyId,algorithm:'Ed25519',payloadBase64:(c.outerPayload||c.payload).toString('base64'),signatureBase64:emittedSignature.toString('base64')};
 const expected={caseId:c.name,outcome:c.expected,stored:c.stored||'none',syntaxError:['INVALID_PAYLOAD','UNSUPPORTED_FORMAT','SIZE_LIMIT'].includes(c.expected)?c.expected:null,
  signedPayloadBytes:c.payload.length,finalByte:c.payload.at(-1),keyId:envelope.keyId,signedKeyId:keyId,clock:1793578000};
 for(const [name,bytes] of Object.entries({'payload.bin':c.payload,'preimage.bin':preimage,'preimage.hex':Buffer.from(preimage.toString('hex')+'\n'),'signature.bin':signature,
  'envelope.json':Buffer.from(JSON.stringify(envelope)+'\n'),'expected-results.json':Buffer.from(JSON.stringify(expected,null,2)+'\n')}))write(`${c.name}/${name}`,bytes);
 expectations.push(`${c.name}\t${c.expected}\t${c.stored||'none'}`);
}
write('expectations.tsv',Buffer.from(expectations.join('\n')+'\n'));
write('configuration.json',Buffer.from(JSON.stringify({TEST_ONLY:true,clock:1793578000,appId:base.appId,channel:'preview',target:'windows-x64',installedVersion:'0.1.0',
 isWindows:true,windowsBuild:26100,workerProtocols:[1],installedDatabaseSchema:1,migrationPaths:[[1,1]],artifactHosts:['releases.example.invalid'],bootstrap:{sequence:10,version:'0.1.0',digest:null},
 trustedKeyIds:[id,rotated],conflictState:{sequence:20,version:'0.2.0',digest:crypto.createHash('sha256').update(plain).digest('hex'),highestVersion:'0.2.0',lastEvaluationTime:1793578000}},null,2)+'\n'));
fs.writeFileSync(path.join(root,'sha256.json'),JSON.stringify(inventory,null,2)+'\n');
console.log(`TEST-ONLY release corpus: ${cases.length} cases, Node ${process.version}, OpenSSL ${process.versions.openssl}`);