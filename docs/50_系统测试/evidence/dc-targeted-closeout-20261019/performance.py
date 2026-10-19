from support import *
from ws_client import Stomp
import xml.etree.ElementTree as E,csv,math,hashlib,collections
from concurrent.futures import ThreadPoolExecutor
s=load_sessions();sa=s['superadmin']['token'];pool=json.loads((PRIVATE/'pool.json').read_text('utf-8'))
JDK='C:/Program Files/Java/jdk-17.0.4.1/bin/java.exe';JMETER='D:/Tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar'
(PRIVATE/'accounts.csv').write_text(''.join(x['username']+',Resident123456,'+str(x['workOrderId'])+'\n' for x in pool),'utf-8')
ws=[];messages=[];progress={'phase':'connecting','durationSeconds':660,'rampSeconds':60,'steadySeconds':600,'httpThreads':50,'registeredResidentAccounts':200}
def prop(parent,name,value,kind='stringProp'):E.SubElement(parent,kind,{'name':name}).text=str(value)
def node(parent,tag,name,gui=None):
 element=E.SubElement(parent,tag,{'testclass':tag,'guiclass':gui or tag+'Gui','testname':name,'enabled':'true'});tree=E.SubElement(parent,'hashTree');return element,tree
def sampler(parent,name,path,method='GET',body=None):
 n,t=node(parent,'HTTPSamplerProxy',name,'HttpTestSampleGui')
 for k,v in [('domain','127.0.0.1'),('port','8087'),('protocol','http'),('path',path),('method',method),('connect_timeout','10000'),('response_timeout','10000')]:prop(n,'HTTPSampler.'+k,v)
 prop(n,'HTTPSampler.use_keepalive','true','boolProp')
 if body is not None:
  prop(n,'HTTPSampler.postBodyRaw','true','boolProp');a=E.SubElement(n,'elementProp',{'name':'HTTPsampler.Arguments','elementType':'Arguments'});c=E.SubElement(a,'collectionProp',{'name':'Arguments.arguments'});z=E.SubElement(c,'elementProp',{'name':'','elementType':'HTTPArgument'});prop(z,'HTTPArgument.always_encode','false','boolProp');prop(z,'Argument.value',body);prop(z,'Argument.metadata','=')
 assertion,at=node(t,'ResponseAssertion','HTTP 200','AssertionGui');c=E.SubElement(assertion,'collectionProp',{'name':'Asserion.test_strings'});prop(c,'200','200');prop(assertion,'Assertion.test_field','Assertion.response_code');prop(assertion,'Assertion.test_type',8,'intProp')
 assertion,at=node(t,'JSONPathAssertion','business code 200','JSONPathAssertionGui');prop(assertion,'JSON_PATH','$.code');prop(assertion,'EXPECTED_VALUE','200');prop(assertion,'JSONVALIDATION','true','boolProp')
 return t
def header(parent,values):
 n,t=node(parent,'HeaderManager','请求头','HeaderPanel');c=E.SubElement(n,'collectionProp',{'name':'HeaderManager.headers'})
 for k,v in values.items():e=E.SubElement(c,'elementProp',{'name':k,'elementType':'Header'});prop(e,'Header.name',k);prop(e,'Header.value',v)
def dataset(parent,file,variables,recycle):
 n,t=node(parent,'CSVDataSet','本批隔离账号池','TestBeanGUI')
 for k,v in [('filename',str(file).replace('\\','/')),('variableNames',variables),('shareMode','shareMode.all'),('delimiter',',')]:prop(n,k,v)
 prop(n,'recycle',str(recycle).lower(),'boolProp');prop(n,'stopThread','false' if recycle else 'true','boolProp')
def make_jmx(phase):
 root=E.Element('jmeterTestPlan',{'version':'1.2','properties':'5.0','jmeter':'5.6.3'});outer=E.SubElement(root,'hashTree');plan,t=node(outer,'TestPlan','dc最新非交易七接口压测','TestPlanGui')
 group,g=node(t,'SetupThreadGroup','200账号实际登录','SetupThreadGroupGui');prop(group,'ThreadGroup.num_threads',1,'intProp');prop(group,'ThreadGroup.ramp_time',1,'intProp');loop=E.SubElement(group,'elementProp',{'name':'ThreadGroup.main_controller','elementType':'LoopController','guiclass':'LoopControlPanel','testclass':'LoopController'});prop(loop,'LoopController.loops',200);prop(loop,'LoopController.continue_forever','false','boolProp')
 dataset(g,PRIVATE/'accounts.csv','username,password,wid',False);header(g,{'Content-Type':'application/json'})
 sub=sampler(g,'setup-login','/api/v1/auth/resident/login','POST','{"username":"${username}","password":"${password}"}')
 post,pt=node(sub,'JSONPostProcessor','extract token','JSONPostProcessorGui');prop(post,'JSONPostProcessor.referenceNames','token');prop(post,'JSONPostProcessor.jsonPathExprs','$.data.token');prop(post,'JSONPostProcessor.match_numbers',1);prop(post,'JSONPostProcessor.default_values','TOKEN_NOT_FOUND')
 post,pt=node(sub,'JSR223PostProcessor','write private tokens','TestBeanGUI');prop(post,'scriptLanguage','groovy');prop(post,'script',"assert vars.get('token') != 'TOKEN_NOT_FOUND'; new File('"+str(PRIVATE/('tokens-'+phase+'.csv')).replace('\\','/')+"').append(vars.get('username')+','+vars.get('token')+','+vars.get('wid')+System.lineSeparator())")
 group,g=node(t,'ThreadGroup','50 HTTP users','ThreadGroupGui');prop(group,'ThreadGroup.num_threads',50,'intProp');prop(group,'ThreadGroup.ramp_time',60,'intProp');prop(group,'ThreadGroup.duration',660);prop(group,'ThreadGroup.delay',0);prop(group,'ThreadGroup.scheduler','true','boolProp');loop=E.SubElement(group,'elementProp',{'name':'ThreadGroup.main_controller','elementType':'LoopController','guiclass':'LoopControlPanel','testclass':'LoopController'});prop(loop,'LoopController.loops',-1);prop(loop,'LoopController.continue_forever','false','boolProp')
 dataset(g,PRIVATE/('tokens-'+phase+'.csv'),'tuser,token,wid',True);header(g,{'Authorization':'Bearer ${token}'})
 timer,tt=node(g,'ConstantTimer','500ms pacing','ConstantTimerGui');prop(timer,'ConstantTimer.delay',500)
 for name,path in [('L1-communities','/communities?page=1&size=20'),('L2-houses','/units/1/houses?page=1&size=20'),('L3-notices','/notices?page=1&size=20'),('L4-workorders','/work-orders?page=1&size=20'),('L5-notifications','/notifications?page=1&size=20'),('L6-workorder-detail','/work-orders/${wid}'),('L7-housing-detail','/housings/1')]:sampler(g,name,'/api/v1'+path)
 E.indent(root);jmx=OUT/('load-'+phase+'.jmx');E.ElementTree(root).write(jmx,encoding='UTF-8',xml_declaration=True);return jmx

def quant(values,p):return sorted(values)[max(0,math.ceil(len(values)*p)-1)]
def summary(phase,path):
 rows=list(csv.DictReader(path.open(encoding='utf-8-sig')));business=[r for r in rows if r['label'].startswith('L')];start=min(int(r['timeStamp']) for r in business);steady=[r for r in business if int(r['timeStamp'])>=start+60000]
 result={'phase':phase,'rawSHA256':hashlib.sha256(path.read_bytes()).hexdigest(),'allSamples':len(rows),'setupLogins':sum(r['label']=='setup-login' for r in rows),'setupFailed':sum(r['label']=='setup-login' and r['success']!='true' for r in rows),'businessSamples':len(business),'steadySamples':len(steady),'steadyDurationSeconds':round((max(int(r['timeStamp']) for r in steady)-(start+60000))/1000,2),'maxHTTPThreads':max(int(r.get('allThreads','0')) for r in business),'failedSamples':[{'label':r['label'],'code':r['responseCode'],'message':r.get('responseMessage')} for r in rows if r['success']!='true'],'labels':{}}
 for label in sorted({r['label'] for r in steady}):
  selected=[r for r in steady if r['label']==label];vals=[int(r['elapsed']) for r in selected];result['labels'][label]={'samples':len(vals),'P90ms':quant(vals,.90),'P95ms':quant(vals,.95),'P99ms':quant(vals,.99),'maxMs':max(vals),'errors':sum(r['success']!='true' for r in selected),'throughputPerSecond':round(len(vals)/600,3),'elapsedHistogram':dict(sorted(collections.Counter(vals).items()))}
 save('performance-'+phase+'.json',result);return result

def seed_bulk():
 before={t:int(sql('SELECT COUNT(*) FROM '+t)) for t in ['community','house','work_order']}
 for c in range(1,6):
  sql("INSERT INTO community(id,name,address,business_code) SELECT %d,'dc规模社区%d','隔离规模数据','%d' WHERE NOT EXISTS(SELECT 1 FROM community WHERE id=%d)"%(c,c,c,c))
  sql("INSERT INTO building(community_id,name,floors) SELECT %d,'dc规模楼',10 WHERE NOT EXISTS(SELECT 1 FROM building WHERE community_id=%d)"%(c,c));bid=int(sql('SELECT MIN(id) FROM building WHERE community_id='+str(c)))
  sql("INSERT INTO unit(building_id,community_id,name) SELECT %d,%d,'dc规模单元' WHERE NOT EXISTS(SELECT 1 FROM unit WHERE building_id=%d)"%(bid,c,bid));uid=int(sql('SELECT MIN(id) FROM unit WHERE building_id='+str(bid)))
  digits=' UNION ALL '.join('SELECT '+str(i)+' AS n' for i in range(10));numbers='SELECT a.n+10*b.n+100*c.n AS n FROM ('+digits+') a CROSS JOIN ('+digits+') b CROSS JOIN ('+digits+') c'
  sql("INSERT INTO house(unit_id,community_id,house_number,floor,area,layout,status) SELECT %d,%d,CAST(10000+n AS CHAR),MOD(n,(SELECT floors FROM building WHERE id=%d))+1,88,'两室一厅','VACANT' FROM (%s) nums"%(uid,c,bid,numbers))
  sql("INSERT INTO service_category(community_id,name,sort_order,is_active) SELECT %d,'dc规模类别',0,1 WHERE NOT EXISTS(SELECT 1 FROM service_category WHERE community_id=%d)"%(c,c));cat=int(sql('SELECT MIN(id) FROM service_category WHERE community_id='+str(c)))
  rid=1 if c==1 else c+1;hid=int(sql('SELECT MIN(id) FROM house WHERE community_id='+str(c)));sql("INSERT INTO residence_relation(resident_id,community_id,house_id,relation_type,move_in_date,is_primary) SELECT %d,%d,%d,'OWNER',CURDATE(),0 WHERE NOT EXISTS(SELECT 1 FROM residence_relation WHERE resident_id=%d AND community_id=%d AND house_id=%d AND move_out_date IS NULL)"%(rid,c,hid,rid,c,hid))
  nums='SELECT n FROM ('+numbers+') x UNION ALL SELECT n+1000 FROM ('+numbers+') y'
  sql("INSERT INTO work_order(order_no,resident_id,community_id,category_id,title,content,contact_phone,status,created_at) SELECT CONCAT('DC-N7-%d-',n),%d,%d,%d,'dc规模工单','合成测试数据','13800000001','COMPLETED',DATE_SUB(NOW(),INTERVAL MOD(n,365) DAY) FROM (%s) z"%(c,rid,c,cat,nums))
 after={t:int(sql('SELECT COUNT(*) FROM '+t)) for t in before};per=sql('SELECT community_id,COUNT(*) FROM house GROUP BY community_id');save('bulk-data.json',{'before':before,'after':after,'housesPerCommunity':per,'normalDatabaseTouched':False});assert after['community']>=5 and after['house']>=5000 and after['work_order']>=10000

def push_probe(recipient,round_idx):
 token=pool[100+round_idx]['token'];client=ws[round_idx];order=api('/work-orders',token,'POST',{'categoryId':1,'title':'dc负载时延'+str(round_idx),'content':'隔离推送时延采样','contactPhone':'13800000001'})
 start=time.perf_counter();api('/work-orders/'+str(order['id'])+'/assign',sa,'PATCH',{'assigneeId':s['staff1']['user']['id']})
 deadline=time.time()+5
 while time.time()<deadline:
  matches=[m for m in client.messages if m['payload'].get('sourceId')==order['id']]
  if matches:break
  time.sleep(.02)
 matches=[m for m in client.messages if m['payload'].get('sourceId')==order['id']]
 messages.append({'round':round_idx,'orderId':order['id'],'received':bool(matches),'conservativeRequestStartToTerminalMs':round((matches[0]['monotonic']-start)*1000,3) if matches else None,'scope':'真实DB事务业务API→STOMP终端；请求开始早于提交，所测为提交后时延上界'})

def phase(name):
 assert not (PRIVATE/('tokens-'+name+'.csv')).exists(),'不覆盖旧执行令牌文件'
 jmx=make_jmx(name);log=PRIVATE/('jmeter-'+name+'.log');jtl=PRIVATE/('samples-'+name+'.jtl')
 with (PRIVATE/('jmeter-'+name+'-console.log')).open('wb') as output:
  proc=subprocess.Popen([JDK,'-Xms256m','-Xmx768m','-jar',JMETER,'-n','-t',str(jmx),'-l',str(jtl),'-j',str(log),'-Jjmeter.save.saveservice.output_format=csv','-Jjmeter.save.saveservice.print_field_names=true','-Jjmeter.save.saveservice.response_data=false','-Jjmeter.save.saveservice.samplerData=false','-Jjmeter.save.saveservice.requestHeaders=false','-Jjmeter.save.saveservice.responseHeaders=false'],cwd=PRIVATE,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  began=time.time();probed=False
  while proc.poll() is None:
   live=sum(x.connected for x in ws);progress.update({'phase':name,'elapsedSeconds':round(time.time()-began),'wsConnected':live});save('performance-progress.json',progress)
   if not probed and time.time()-began>130:
    for probe_idx in range(3):push_probe('pool',probe_idx)
    probed=True
   time.sleep(5)
  assert proc.returncode==0,'JMeter failed; inspect private log'
 result=summary(name,jtl);result['wsConnectedAtEnd']=sum(x.connected for x in ws);result['wsErrors']=[err for x in ws for err in x.errors];save('performance-'+name+'.json',result);print(name,'completed',result['businessSamples'],'samples',flush=True);return result
try:
 for row in pool[100:150]:ws.append(Stomp(row['token']))
 assert len(ws)==50 and all(x.connected for x in ws)
 base=phase('base');seed_bulk();bulk=phase('bulk')
 comparison={label:{'baseP95ms':base['labels'][label]['P95ms'],'bulkP95ms':bulk['labels'][label]['P95ms'],'changePercent':round((bulk['labels'][label]['P95ms']/base['labels'][label]['P95ms']-1)*100,2)} for label in base['labels']}
 save('performance.json',{'baseSummary':'performance-base.json','bulkSummary':'performance-bulk.json','comparison':comparison,'pushSamples':messages,'allP95Within2s':all(v['bulkP95ms']<=2000 and v['baseP95ms']<=2000 for v in comparison.values()),'allPushWithin3s':all(x['received'] and x['conservativeRequestStartToTerminalMs']<=3000 for x in messages),'functionalErrors':len(base['failedSamples'])+len(bulk['failedSamples']),'wsUsers':50,'registeredResidents':200,'normalServicesTouched':False})
finally:
 for x in ws:x.close()
progress['phase']='complete';save('performance-progress.json',progress)
