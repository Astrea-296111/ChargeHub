from pathlib import Path
import json,re,uuid
from decimal import Decimal,ROUND_HALF_UP
import yaml

ROOT=Path(__file__).resolve().parents[1]
checks=[]
def ok(name,details): checks.append({'check':name,'status':'passed','details':details})

v=json.loads((ROOT/'07-billing-vectors.json').read_text())
rounded=lambda x:int(Decimal(x).quantize(Decimal('1'),rounding=ROUND_HALF_UP))
for case in v['vectors']:
    energy=sum(rounded(Decimal(s['wh'])*s['energyRateFenPerKwh']/1000) for s in case['segments'])
    service=sum(rounded(Decimal(s['wh'])*s['serviceRateFenPerKwh']/1000) for s in case['segments'])
    member=min(rounded(Decimal(service)*Decimal('0.2')),300) if case['member'] else 0
    eligible=service-member
    coupon=min(eligible,case['coupon']['discountFen']) if case['coupon'] and eligible>=case['coupon']['thresholdFen'] else 0
    payable=energy+service-member-coupon
    actual={'totalWh':sum(s['wh'] for s in case['segments']),'energyFen':energy,'serviceFen':service,'memberDiscountFen':member,'couponDiscountFen':coupon,'payableFen':payable,'refundFen':max(case['prepaidFen']-payable,0),'memberConsumed':member>0,'couponUsed':coupon>0,'shortfallFen':max(payable-case['prepaidFen'],0)}
    assert actual==case['expected'],(case['id'],actual,case['expected'])
    assert 0<=coupon+member<=service
    ok(case['id'],actual)

api=yaml.safe_load((ROOT/'04-openapi.yaml').read_text())
assert api['openapi']=='3.0.3'
def walk(o):
    if isinstance(o,dict):
        if '$ref' in o:
            assert o['$ref'].startswith('#/')
            n=api
            for p in o['$ref'][2:].split('/'):n=n[p]
        for x in o.values():walk(x)
    elif isinstance(o,list):
        for x in o:walk(x)
walk(api)
ids=[]
for path,item in api['paths'].items():
    for method,op in item.items():
        ids.append(op['operationId'])
        params=op.get('parameters',[])
        declared={x['name'] for x in params if x.get('in')=='path'}
        assert set(re.findall(r'\{([^}]+)\}',path))==declared,(path,declared)
        if method=='post' and not path.startswith('/integrations') and path not in ['/v1/auth/token','/v1/charge-quotes']:
            assert {'$ref':'#/components/parameters/IdempotencyKey'} in params,path
        assert op['responses']
assert len(ids)==len(set(ids))
ok('OpenAPI structural checks',{'operations':len(ids),'schemas':len(api['components']['schemas']),'checks':['YAML parse','all internal refs resolved','operationId unique','path parameters declared','business writes require idempotency key']})

schema=json.loads((ROOT/'05-events.schema.json').read_text())
# A deterministic checker for exactly the JSON Schema keywords used here.
# This is not a replacement for a complete OpenAPI or JSON Schema validator.
def validate(node,ins):
    if '$ref' in node:
        target=schema
        for p in node['$ref'][2:].split('/'):target=target[p]
        return validate(target,ins)
    if 'const' in node and ins!=node['const']:return False
    if 'enum' in node and ins not in node['enum']:return False
    t=node.get('type')
    if t=='object' and not isinstance(ins,dict):return False
    if t=='string' and not isinstance(ins,str):return False
    if t=='integer' and (not isinstance(ins,int) or isinstance(ins,bool)):return False
    if isinstance(ins,dict):
        if not set(node.get('required',[])).issubset(ins):return False
        props=node.get('properties',{})
        if node.get('additionalProperties') is False and not set(ins).issubset(props):return False
        if any(not validate(p,ins[k]) for k,p in props.items() if k in ins):return False
    if isinstance(ins,str):
        if len(ins)<node.get('minLength',0) or len(ins)>node.get('maxLength',10**9):return False
        if 'pattern' in node and not re.search(node['pattern'],ins):return False
        if node.get('format')=='uuid':
            try:uuid.UUID(ins)
            except ValueError:return False
        if node.get('format')=='date-time' and not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z',ins):return False
    if isinstance(ins,int) and not isinstance(ins,bool):
        if ins<node.get('minimum',-10**30) or ins>node.get('maximum',10**30):return False
    if 'oneOf' in node and sum(validate(b,ins) for b in node['oneOf'])!=1:return False
    return True

uid='00000000-0000-4000-8000-000000000001'
now='2026-10-18T07:00:00Z'
def sample(node):
    if '$ref' in node:
        n=schema
        for p in node['$ref'][2:].split('/'):n=n[p]
        return sample(n)
    if 'const' in node:return node['const']
    if 'enum' in node:return node['enum'][0]
    if node.get('type')=='integer':return max(node.get('minimum',0),1)
    if node.get('type')=='object':return {k:sample(x) for k,x in node['properties'].items() if k in node.get('required',[])}
    if node.get('format')=='uuid':return uid
    if node.get('format')=='date-time':return now
    if node.get('pattern')=='^[1-9][0-9]{0,18}$':return '1'
    if node.get('pattern')=='^[0-9a-f]{64}$':return 'a'*64
    return 'sample'

for name,node in schema['$defs'].items():
    if not name.endswith('Payload'):continue
    event={'eventId':uid,'eventType':name[:-7],'schemaVersion':1,'aggregateType':'ChargeOrder','aggregateId':uid,'aggregateVersion':1,'occurredAt':now,'traceId':'trace-001','payload':sample(node)}
    assert validate(schema,event),(name,event)
    bad=dict(event,payload={})
    assert not validate(schema,bad),name
    ok('Event '+event['eventType'],'valid payload accepted, missing required payload rejected')
device={'eventId':uid,'deviceId':'sim-01','connectorId':'1','sessionNo':uid,'eventType':'STOPPED','seqNo':5,'meterWh':120000,'finalSeq':5,'occurredAt':now,'sampleKind':'END'}
assert validate(schema,device)
bad=dict(device);bad.pop('finalSeq')
assert not validate(schema,bad)
bad=dict(device,meterWh=-1);assert not validate(schema,bad)
bad=dict(device,eventId='invalid');assert not validate(schema,bad)
ok('Device event checks','valid STOPPED accepted; missing finalSeq, negative Wh and invalid UUID rejected')

sql=(ROOT/'03-schema.sql').read_text()
tables=re.findall(r'CREATE TABLE (\w+)\s*\((.*?)\) ENGINE=',sql,re.S)
assert len(tables)==30 and len({n for n,b in tables})==30
for name,body in tables:
    assert 'PRIMARY KEY' in body,name
assert 'DROP TABLE' not in sql.upper()
assert sql.count('ENGINE=InnoDB')==30
assert "SET time_zone = '+00:00'" in sql
need=['uk_pay_order','uk_pay_provider','uk_cash_biz','uk_cash_provider','uk_coupon_user_template','uk_meter_session_seq','uk_outbox_event','uk_quota_order_action','uk_bill_order','uk_refund_reason']
for key in need:assert key in sql,key
ok('SQL static checks',{'tables':30,'checks':['unique table names','primary keys','InnoDB','critical unique constraints','UTC initialization','no destructive DROP']})

cases=re.findall(r'^\| (A\d+) \|',(ROOT/'06-acceptance.md').read_text(),re.M)
assert len(cases)==47 and len(set(cases))==47
tasks=re.findall(r'^## (T\d+) ',(ROOT/'02-codex-tasks.md').read_text(),re.M)
assert len(tasks)==12 and tasks[0]=='T00' and tasks[-1]=='T11'
ok('Plan coverage',{'acceptanceScenarios':47,'tasks':12})

report={'checkedAt':'2026-10-18','scope':'Design arithmetic and structural consistency only; no running application has been built.','results':checks,'pending':['Execute Flyway migration in real MySQL 8.4','Run full OpenAPI 3.0.3 and JSON Schema 2020-12 validators','Build locked Java dependency graph','Execute all business concurrency and failure acceptance scenarios','Run load tests and deployment restore drill']}
(ROOT/'12-design-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passedChecks':len(checks),'billingVectors':8,'tables':30,'operations':25,'eventTypes':9,'scenarios':47,'tasks':12,'applicationTested':False},ensure_ascii=False))
