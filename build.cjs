const fs=require('node:fs'),path=require('node:path');
let html=fs.readFileSync(path.join(__dirname,'dist/index.html'),'utf8');
html=html.replace('<link rel="stylesheet" href="style.css">',()=>'<style>'+fs.readFileSync(path.join(__dirname,'dist/style.css'),'utf8')+'</style>');
for(const file of ['connection.js','app.js'])html=html.replace(`<script src="${file}"></script>`,()=>'<script>'+fs.readFileSync(path.join(__dirname,'dist',file),'utf8')+'</script>');
fs.writeFileSync(path.join(__dirname,'../思辨场.html'),html);
console.log('Standalone demo rebuilt. Real API requires the local server.');
