const {test}=require('node:test'),assert=require('node:assert/strict');
const {createServer}=require('../server.cjs');
test('user API stays isolated in memory; generation uses chosen provider and rejects foreign origins',async(t)=>{
 let upstream;
 const server=createServer({fetchImpl:async(url,options)=>{upstream={url,options};return new Response(JSON.stringify({choices:[{message:{content:'连接成功。'},finish_reason:'stop'}]}));}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}));
 const origin=`http://127.0.0.1:${server.address().port}`;let cookie='';
 async function call(url,method='GET',body,headers={}){const r=await fetch(origin+url,{method,headers:{'Content-Type':'application/json','X-Sibian-Client':'web',Origin:origin,Cookie:cookie,...headers},body:body?JSON.stringify(body):undefined});if(r.headers.get('set-cookie'))cookie=r.headers.get('set-cookie').split(';')[0];return {status:r.status,body:await r.json()};}
 assert.equal((await call('/api/test','POST')).status,409);
 const configured=await call('/api/config','POST',{baseURL:'https://example.com/v1',apiKey:'test-secret-only',model:'my-model'});
 assert.equal(configured.status,200);assert.equal(configured.body.configured,true);assert.ok(!JSON.stringify(configured).includes('test-secret-only'));
 assert.equal((await call('/api/test','POST',{})).body.text,'连接成功。');
 assert.equal(upstream.url,'https://example.com/v1/chat/completions');assert.equal(upstream.options.headers.Authorization,'Bearer test-secret-only');
 assert.equal((await call('/api/config','GET',null,{Origin:'https://foreign.example'})).status,403);
 const isolated=await fetch(origin+'/api/config',{headers:{'X-Sibian-Client':'web'}});assert.equal((await isolated.json()).configured,false);
 assert.equal((await call('/api/config','DELETE')).body.configured,false);
 assert.equal((await call('/api/test','POST')).status,409);
});
