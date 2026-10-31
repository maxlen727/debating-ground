const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {ApiError,normalizeConfig,buildRequest,finalize,complete}=require('./ai.cjs');
function createServer({fetchImpl=fetch,timeoutMs=90000}={}){
 const root=path.join(__dirname,'dist'),sessions=new Map();
 function json(res,status,data){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data));}
 async function body(req){const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>1000000)throw new ApiError('请求内容过大。',413);chunks.push(chunk);}try{return JSON.parse(Buffer.concat(chunks).toString('utf8')||'{}');}catch{throw new ApiError('请求格式错误。');}}
 return http.createServer(async(req,res)=>{
  res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');
  let url;try{url=new URL(req.url,'http://'+req.headers.host);}catch{return json(res,400,{error:'无效地址'});}
  if(!['localhost','127.0.0.1'].includes(url.hostname))return json(res,403,{error:'仅支持本机访问。'});
  if(url.pathname.startsWith('/api/')){
   if(req.headers['x-sibian-client']!=='web'||(req.headers.origin&&req.headers.origin!==url.origin))return json(res,403,{error:'请从本机思辨场页面访问。'});
   let id=req.headers.cookie?.match(/(?:^|;\s*)sibian_session=([a-f0-9]{48})(?:;|$)/)?.[1],state=sessions.get(id);
   if(!state){id=crypto.randomBytes(24).toString('hex');state={config:null,active:null,touched:Date.now()};sessions.set(id,state);res.setHeader('Set-Cookie',`sibian_session=${id}; HttpOnly; SameSite=Strict; Path=/`);}
   state.touched=Date.now();for(const[k,v]of sessions)if(!v.active&&Date.now()-v.touched>8*3600000)sessions.delete(k);
   const publicState=()=>({configured:!!state.config,verified:!!state.verified,baseURL:state.config?.endpoint||'',model:state.config?.model||''});
   try{
    if(req.method==='GET'&&url.pathname==='/api/config')return json(res,200,publicState());
    if(req.method==='POST'&&url.pathname==='/api/config'){
     if(state.active)throw new ApiError('请先取消当前请求。',409);
     const value=await body(req);state.config=normalizeConfig(value);state.verified=false;return json(res,200,publicState());
    }
    if(req.method==='DELETE'&&url.pathname==='/api/config'){state.active?.abort();state.config=null;state.verified=false;return json(res,200,publicState());}
    if(req.method==='POST'&&['/api/test','/api/generate'].includes(url.pathname)){
     if(!state.config)throw new ApiError('请先填写并连接 API。',409);
     if(state.active)throw new ApiError('已有请求正在进行，请等待或取消。',409);
     const data=await body(req),request=buildRequest(url.pathname==='/api/test'?'test':data.purpose,data.payload);
     if(state.active)throw new ApiError('已有请求正在进行。',409);
     if(!state.config)throw new ApiError('API 已断开连接。',409);
     state.verified=false;
     const controller=new AbortController();state.active=controller;res.on('close',()=>{if(!res.writableEnded)controller.abort();});
     try{const result=await complete(state.config,request.messages,{fetchImpl,timeoutMs,signal:controller.signal});state.verified=true;const output=finalize(request,result);if(!res.destroyed)json(res,200,output);}finally{if(state.active===controller)state.active=null;}return;
    }
    return json(res,404,{error:'接口不存在。'});
   }catch(e){if(!res.destroyed)json(res,e instanceof ApiError?e.status:500,{error:e instanceof ApiError?e.message:'本机服务未能完成请求。'});return;}
  }
  if(req.method!=='GET'&&req.method!=='HEAD')return json(res,405,{error:'方法不支持'});
  let name;try{name=decodeURIComponent(url.pathname);}catch{return json(res,400,{error:'地址无效'});}
  const file=path.resolve(root,'.'+(name==='/'?'/index.html':name));if(!file.startsWith(root+path.sep))return json(res,403,{error:'禁止访问'});
  fs.readFile(file,(err,data)=>{if(err)return json(res,404,{error:'文件不存在'});res.setHeader('Cache-Control','no-cache');res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8'})[path.extname(file)]||'application/octet-stream');res.end(req.method==='HEAD'?undefined:data);});
 });
}
if(require.main===module){const server=createServer();server.on('error',e=>{console.error(e.code==='EADDRINUSE'?'端口 4173 已被占用。请关闭旧版本服务后重新启动。':'本机服务启动失败。');process.exitCode=1;});server.listen(4173,'127.0.0.1',()=>console.log('思辨场 v0.4 已启动：http://127.0.0.1:4173\n请在浏览器内填写 API。密钥仅保存在本次服务内存中。关闭本窗口停止服务。'));}
module.exports={createServer};
