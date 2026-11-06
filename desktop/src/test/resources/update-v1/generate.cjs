// Public TEST-ONLY RFC 8032 section 7.1 seed. Never a deployment key.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const seed = Buffer.from('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60','hex');
const privateKey = crypto.createPrivateKey({key:Buffer.concat([Buffer.from('302e020100300506032b657004220420','hex'),seed]),format:'der',type:'pkcs8'});
const pub = crypto.createPublicKey(privateKey).export({format:'der',type:'spki'});
const payload = Buffer.from('{"schemaVersion":1,"note":"TEST ONLY — Riff"}\n','utf8'); // verifier opaque payload, deliberately not Module 3 schema
const id = Buffer.from('test-rfc8032-01'); const algorithm = Buffer.from('Ed25519');
const u16 = n => { const b=Buffer.alloc(2); b.writeUInt16BE(n); return b; };
const u32 = n => { const b=Buffer.alloc(4); b.writeUInt32BE(n); return b; };
const message = Buffer.concat([Buffer.from('RIFF-DESKTOP-UPDATE\0','ascii'),u32(1),u16(id.length),id,u16(algorithm.length),algorithm,u32(payload.length),payload]);
const signature = crypto.sign(null,message,privateKey);
if(!crypto.verify(null,message,crypto.createPublicKey(privateKey),signature)) throw Error('OpenSSL verification failed');
const files = {'public-key.hex':Buffer.from(pub.subarray(12).toString('hex')+'\n'),'expected-results.json':Buffer.from(JSON.stringify({keyId:id.toString(),appIds:['com.musicglass.desktop'],channels:['preview'],targets:['windows-x64'],allowTestKeys:true,verification:'VERIFIED_OPAQUE_PAYLOAD',payloadSchemaEvaluated:false,eligibilityEvaluated:false},null,2)+'\n'),'public-key.der':pub,'payload.bin':payload,'preimage.bin':message,'preimage.hex':Buffer.from(message.toString('hex')+'\n'),'signature.bin':signature,'envelope.json':Buffer.from(JSON.stringify({envelopeVersion:1,keyId:id.toString(),algorithm:'Ed25519',payloadBase64:payload.toString('base64'),signatureBase64:signature.toString('base64')})+'\n')};
for(const [name,bytes] of Object.entries(files)) fs.writeFileSync(path.join(__dirname,name),bytes);
fs.writeFileSync(path.join(__dirname,'sha256.json'),JSON.stringify(Object.fromEntries(Object.entries(files).map(([n,b])=>[n,crypto.createHash('sha256').update(b).digest('hex')])),null,2)+'\n');
console.log('Node/OpenSSL fixed Riff preimage signed and independently verified');
