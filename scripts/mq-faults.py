#!/usr/bin/env python3
"""Real broker A40/A41/A42: scoped worker barriers, external SIGKILL, redelivery, DLQ and audited replay.

Run serially against the dev MQ topology, after a normal demo has produced one completed refund.
Creates two new ordinary-user charging transactions through the API for A41. Existing money facts
are read, never rewritten. The only SQL fixtures are dedicated users and A40/A42 Outbox intents.
Ordinary worker containers are stopped for deterministic ownership and restored in finally.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
from demo import Client, available_connectors, expect, wait_for

ROOT=Path(__file__).resolve().parents[1]


def literal(value):
    return "CONVERT(0x"+str(value).encode().hex()+" USING utf8mb4)"


class FaultDriver:
    def __init__(self,args):
        self.args=args;self.env=dict(os.environ);self.containers=[];self.consumer=None;self.publisher=None
        for line in (ROOT/'.local/dev.env').read_text().splitlines():
            if line and not line.startswith('#'):
                key,value=line.split('=',1);self.env.setdefault(key,value)
        self.env.setdefault('DOCKER_CONFIG',str(ROOT/'.local/docker'))
        self.base=['docker','compose','-p',args.project,'--env-file',str(ROOT/'.local/dev.env'),'-f',str(ROOT/'compose.yaml')]
        self.mq=self.base+['-f',str(ROOT/'compose-mq.yaml')]
        self.prefix='mq-fault-'+uuid.uuid4().hex[:8];self.worker_count=0;self.restore_mq=True
        self.gateway=Client(args.gateway,control_key=self.env['CHARGEHUB_CONTROL_KEY'])
        self.device=Client(args.device,control_key=self.env['CHARGEHUB_CONTROL_KEY'])
        self.admin=Client(args.api)
        self.report={'startedAt':datetime.now(timezone.utc).isoformat(),'simulated':True,'broker':'5.5.0','nativeClient':'5.2.2','cases':[]}

    def run(self,command,check=True,input=None):
        result=subprocess.run(command,env=self.env,input=input,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
        if check and result.returncode:
            raise RuntimeError('Command failed ('+str(result.returncode)+'): '+command[0]+' '+command[1]+'; '+result.stderr[-800:])
        return result

    def sql(self,sql):
        result=self.run(self.base+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" --batch --raw --skip-column-names "$MYSQL_DATABASE"'],input=sql)
        return [line for line in result.stdout.splitlines() if line]

    def one(self,sql):
        lines=self.sql(sql)
        return json.loads(lines[0]) if lines else None

    def observe(self,description,fetch,predicate=lambda value:bool(value),timeout=None):
        deadline=time.monotonic()+(timeout or self.args.timeout)
        while time.monotonic()<deadline:
            value=fetch()
            if predicate(value):return value
            time.sleep(.5)
        raise AssertionError('Timed out observing '+description)

    def start_worker(self,role,publish=False,consume=False,phase='',event_id='',order_no=''):
        name=self.prefix+'-'+role+'-'+uuid.uuid4().hex[:5]
        values={'CHARGEHUB_MESSAGING_PUBLISH_ENABLED':str(publish).lower(),'CHARGEHUB_MESSAGING_CONSUME_ENABLED':str(consume).lower(),
                'CHARGEHUB_MESSAGING_LEASE_SECONDS':'5','CHARGEHUB_MESSAGING_INVISIBLE_SECONDS':'10','CHARGEHUB_MESSAGING_MAX_ATTEMPTS':'3',
                'CHARGEHUB_MESSAGING_FAILPOINT_PHASE':phase,'CHARGEHUB_MESSAGING_FAILPOINT_EVENT_ID':event_id,
                'CHARGEHUB_MESSAGING_FAILPOINT_ORDER_NO':order_no,'CHARGEHUB_MESSAGING_FAILPOINT_EVENT_NAME':'RefundRequested' if order_no else ''}
        command=self.mq+['run','-d','--no-deps','--name',name]
        for key,value in values.items():command+=['-e',key+'='+value]
        self.run(command+['worker']);self.containers.append(name)
        self.observe('worker readiness '+role,lambda:self.run(['docker','inspect','--format','{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}',name]).stdout.strip(),lambda value:value=='healthy')
        return name

    def remove(self,name,kill=False):
        if not name:return
        if kill:self.run(['docker','kill','--signal=KILL',name])
        else:self.run(['docker','stop','--time','5',name],check=False)
        logs=self.run(['docker','logs','--tail','1000',name],check=False)
        log_dir=ROOT/'.local/reports/mq-workers';log_dir.mkdir(parents=True,exist_ok=True)
        log_path=log_dir/(name+'.log');log_path.write_text(logs.stdout+logs.stderr);log_path.chmod(0o600)
        self.report.setdefault('workerLogs',[]).append(str(log_path.relative_to(ROOT)))
        self.run(['docker','rm','-f',name],check=False)
        if name in self.containers:self.containers.remove(name)

    def start_consumer(self,phase='',event_id='',order_no=''):
        self.remove(self.consumer)
        self.consumer=self.start_worker('consumer',consume=True,phase=phase,event_id=event_id,order_no=order_no)
        return self.consumer

    def state(self,event):
        return self.one("SELECT JSON_OBJECT('eventId',o.event_id,'outboxStatus',o.status,'publishAttempts',o.attempt_count,'payloadHash',SHA2(CAST(o.payload AS CHAR),256),"
                        "'deliveryStatus',d.status,'businessAttempts',d.attempt_count,'deliveryToken',d.lease_token,'inboxCount',(SELECT COUNT(*) FROM consumer_inbox i WHERE i.event_id=o.event_id),"
                        "'ackCount',(SELECT COUNT(*) FROM message_ack_evidence a WHERE a.event_id=o.event_id),'duplicateAckCount',(SELECT COUNT(*) FROM message_ack_evidence a WHERE a.event_id=o.event_id AND duplicate_delivery=1),"
                        "'maxAckDeliveryAttempt',(SELECT MAX(delivery_attempt) FROM message_ack_evidence a WHERE a.event_id=o.event_id)) FROM outbox_event o LEFT JOIN message_delivery d ON d.event_id=o.event_id WHERE o.event_id="+literal(event))

    def barrier(self,phase,event=None,order=None):
        condition='f.event_id='+literal(event) if event else "JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.orderNo'))="+literal(order)
        return self.observe(phase,lambda:self.one("SELECT JSON_OBJECT('eventId',f.event_id,'phase',f.phase,'instance',f.instance_id) FROM message_fault_observation f JOIN outbox_event o ON o.event_id=f.event_id WHERE f.phase="+literal(phase)+' AND '+condition+' ORDER BY f.id DESC LIMIT 1'))

    def finished(self,event,duplicate=False):
        return self.observe('real Ack and inbox '+event,lambda:self.state(event),lambda value:value and value['outboxStatus']=='DONE' and value['inboxCount']==1 and value['ackCount']>=1 and (not duplicate or value['duplicateAckCount']>=1 and value['maxAckDeliveryAttempt']>=2))

    def fixture(self):
        return self.one("SELECT JSON_OBJECT('orderNo',r.order_no,'refundNo',r.refund_no,'payNo',r.pay_no) FROM refund_order r JOIN charge_order o ON o.order_no=r.order_no WHERE r.status='SUCCESS' AND o.status='COMPLETED' ORDER BY r.id LIMIT 1")

    def money(self,fixture):
        order=literal(fixture['orderNo']);refund=literal(fixture['refundNo']) if fixture.get('refundNo') else None
        result=self.one("SELECT JSON_OBJECT('orderNo',o.order_no,'status',o.status,'capturedFen',p.captured_fen,'refundSuccessFen',p.refund_success_fen,'refundReservedFen',p.refund_reserved_fen,"
                        "'captureCount',(SELECT COUNT(*) FROM cash_entry c WHERE c.order_no=o.order_no AND c.biz_type='CAPTURE'),'refundCount',(SELECT COUNT(*) FROM cash_entry c WHERE c.order_no=o.order_no AND c.biz_type='REFUND'),"
                        "'sessionCount',(SELECT COUNT(*) FROM charge_session s WHERE s.order_no=o.order_no),'billCount',(SELECT COUNT(*) FROM charge_bill b WHERE b.order_no=o.order_no)) FROM charge_order o JOIN payment_order p ON p.order_no=o.order_no WHERE o.order_no="+order)
        channel=self.gateway.call('/dev/state',{})
        result['channelCaptureCount']=sum(p['outTradeNo']==fixture['payNo'] and p['status']=='SUCCESS' for p in channel['payments'])
        result['channelRefundCount']=sum(r['outTradeNo']==fixture['payNo'] and r['status']=='SUCCESS' for r in channel['refunds'])
        return result

    def enqueue(self,fixture):
        event=str(uuid.uuid4());payload=json.dumps({'orderNo':fixture['orderNo'],'refundNo':fixture['refundNo']},separators=(',',':'))
        self.sql("INSERT INTO outbox_event(event_id,event_name,aggregate_type,aggregate_id,aggregate_version,payload,trace_id) SELECT "+literal(event)+",'RefundRequested','ORDER',"+literal(fixture['refundNo'])+",COALESCE(MAX(aggregate_version),0)+1,"+literal(payload)+','+literal(event)+" FROM outbox_event WHERE event_name='RefundRequested' AND aggregate_id="+literal(fixture['refundNo']))
        return event

    def assert_closed_once(self,fixture):
        money=self.money(fixture)
        expect(money['captureCount']==1 and money['refundCount']==1 and money['sessionCount']==1 and money['billCount']==1,'Expected one local capture/refund/session/bill')
        expect(money['channelCaptureCount']==1 and money['channelRefundCount']==1,'Independent channel duplicated a financial fact')
        expect(money['refundReservedFen']==0 and money['status']=='COMPLETED','Refund or session is not finalized')
        return money

    def published(self,event):
        return self.observe('broker publication',lambda:self.one("SELECT JSON_OBJECT('eventId',event_id,'brokerMessageId',broker_message_id,'publicationCount',publication_count) FROM outbox_publication WHERE event_id="+literal(event)))

    def a40(self,fixture):
        before=self.assert_closed_once(fixture);event=self.enqueue(fixture)
        crashed=self.start_worker('publisher-barrier',publish=True,phase='AFTER_SEND_BEFORE_MARK',event_id=event)
        barrier=self.barrier('AFTER_SEND_BEFORE_MARK',event=event);at_barrier=self.state(event)
        expect(at_barrier['outboxStatus']=='PROCESSING','Publication was marked before the intended crash barrier')
        self.remove(crashed,kill=True)
        self.publisher=self.start_worker('publisher-recovery',publish=True)
        publication=self.published(event)
        expect(self.state(event)['publishAttempts']>=2,'Expired publisher lease did not trigger resend')
        self.start_consumer();after=self.finished(event);self.remove(self.consumer);self.consumer=None
        expect(self.assert_closed_once(fixture)==before,'A40 changed an existing financial fact')
        self.report['cases'].append({'case':'A40','name':'brokerAcceptedBeforeLocalPublicationMark','status':'PASS','eventId':event,'barrier':barrier,'atBarrier':at_barrier,'publication':publication,'after':after,'money':before})

    def fresh_charging_order(self):
        username=self.prefix+'-u-'+uuid.uuid4().hex[:5]
        self.sql("INSERT INTO iam_user(login_name,password_hash) SELECT "+literal(username)+",password_hash FROM iam_user WHERE login_name='customer'; INSERT INTO iam_user_role(user_id,role_code) SELECT id,'CUSTOMER' FROM iam_user WHERE login_name="+literal(username)+';')
        client=Client(self.args.api);client.login(username,self.env['CHARGEHUB_DEMO_PASSWORD'])
        available=available_connectors(client);selected=None
        for station,connector in available:
            quote=client.call('/v1/charge-quotes',{'connectorId':connector['connectorId'],'estimatedEnergyWh':20000,'useMembership':False})
            if 0<quote['amounts']['payableFen']<10000:
                selected=(station,connector);break
        expect(selected is not None,'A41 requires an idle connector whose 20kWh quote leaves a positive balance refund')
        station,connector=selected
        order=client.call('/v1/charge-orders',{'connectorId':connector['connectorId'],'expectedTariffVersion':station['tariffVersion'],'useMembership':False})
        payment=client.call('/v1/charge-orders/'+order['orderNo']+'/payment',{})
        wait_for('real channel trade creation',lambda:self.gateway.call('/dev/payments/'+payment['payNo']+'/capture',{}),lambda value:value.get('status')=='SUCCESS',timeout=self.args.timeout)
        active=wait_for('real device session',lambda:client.call('/v1/charge-orders/'+order['orderNo']),lambda value:value['status']=='CHARGING',timeout=self.args.timeout)
        return client,{'orderNo':order['orderNo'],'payNo':payment['payNo'],'sessionNo':active['sessionNo'],'username':username}

    def a41(self,phase):
        self.start_consumer();client,fixture=self.fresh_charging_order()
        before=self.money(fixture);expect(before['refundCount']==0 and before['channelRefundCount']==0,'A41 requires a genuinely new unpaid refund intention')
        self.start_consumer(phase=phase,order_no=fixture['orderNo'])
        self.device.call('/dev/sessions/'+fixture['sessionNo']+'/demo',{})
        barrier=self.barrier(phase,order=fixture['orderNo']);event=barrier['eventId']
        refund=self.one("SELECT JSON_OBJECT('refundNo',refund_no) FROM refund_order WHERE order_no="+literal(fixture['orderNo']));fixture.update(refund)
        at_barrier=self.state(event);business=self.assert_closed_once(fixture)
        expect(business['refundCount']==1 and before['refundCount']==0,'First refund business transaction did not commit before kill')
        expect(at_barrier['inboxCount']==(1 if phase=='AFTER_INBOX_BEFORE_ACK' else 0),'Inbox barrier is not at the documented boundary')
        expect(at_barrier['ackCount']==0,'Broker Ack happened before the intended barrier')
        self.remove(self.consumer,kill=True);self.consumer=None;self.start_consumer()
        after=self.finished(event,duplicate=phase=='AFTER_INBOX_BEFORE_ACK')
        if phase=='AFTER_BUSINESS_BEFORE_INBOX':expect(after['businessAttempts']>=2,'Business-before-inbox crash did not redeliver')
        final=self.assert_closed_once(fixture);expect(final==business,'Crash recovery duplicated the financial or device effect')
        self.report['cases'].append({'case':'A41','name':phase,'status':'PASS','eventId':event,'newTransaction':True,'barrier':barrier,'before':before,'atBarrier':at_barrier,'businessCommitted':business,'after':after,'money':final})
        self.remove(self.consumer);self.consumer=None

    def dlq(self,event):
        result=self.run(self.mq+['exec','-T','mq-broker','sh','mqadmin','queryMsgByKey','-n','mq-namesrv:9876','-t','%DLQ%chargehub-workflow-v1','-k',event],check=False)
        ids=re.findall(r'^([A-Fa-f0-9]{32,64})\s',result.stdout,re.MULTILINE)
        if ids:return {'messageIds':list(dict.fromkeys(ids)),'topic':'%DLQ%chargehub-workflow-v1','querySucceeded':result.returncode==0}
        return None

    def a42(self,fixture):
        before=self.assert_closed_once(fixture);event=self.enqueue(fixture)
        self.start_consumer(phase='FAIL_BEFORE_BUSINESS',event_id=event);self.published(event)
        dead=self.observe('bounded business failure to DEAD',lambda:self.state(event),lambda value:value and value['outboxStatus']=='DEAD' and value['deliveryStatus']=='DEAD')
        broker_dlq=self.observe('actual broker consumer-group DLQ',lambda:self.dlq(event),timeout=self.args.timeout)
        expect(dead['inboxCount']==0,'Failed delivery was marked completed')
        self.remove(self.consumer);self.consumer=None
        original_hash=dead['payloadHash']
        self.admin.login('admin',self.env['CHARGEHUB_DEMO_PASSWORD'])
        visible=self.admin.call('/v1/admin/outbox?status=DEAD&limit=100')
        expect(any(row['eventId']==event for row in visible['items']),'DEAD event is not visible through the admin API')
        accepted=self.admin.call('/v1/admin/outbox/'+event+'/replay',{'reason':'A42真实Broker死信恢复：修复测试故障后按原事件号重放'})
        expect(accepted['resourceNo']==event,'Manual replay changed the original event ID')
        self.start_consumer();after=self.finished(event)
        expect(after['payloadHash']==original_hash,'Manual replay altered the original payload')
        audit=self.one("SELECT JSON_OBJECT('count',COUNT(*),'operatorId',MAX(operator_id)) FROM ops_audit WHERE action='OUTBOX_REPLAY' AND target_no="+literal(event))
        expect(audit['count']==1 and audit['operatorId']>0,'Replay operator/reason audit was not saved exactly once')
        expect(self.assert_closed_once(fixture)==before,'A42 replay duplicated an existing business effect')
        self.report['cases'].append({'case':'A42','name':'boundedRetryBrokerDlqAndAuditedSameEventReplay','status':'PASS','eventId':event,'dead':dead,'brokerDlq':broker_dlq,'adminVisible':True,'audit':audit,'after':after,'money':before})

    def execute(self):
        tables=int(self.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('message_delivery','message_ack_evidence','message_fault_observation')")[0])
        expect(tables==3,'Run the latest application migrations including V7/V8 before MQ faults')
        expect(self.run(self.mq+['exec','-T','api','sh','-c','test "$CHARGEHUB_MESSAGING_MODE" = rocketmq'],check=False).returncode==0,'API must use compose-mq.yaml so audited replay resets the delivery lease')
        fixture=self.fixture();expect(bool(fixture),'Run a normal demo first to create one completed refund')
        running=self.run(self.base+['ps','-q','worker']).stdout.split();self.worker_count=len(running)
        if running:
            files=self.run(['docker','inspect','--format','{{index .Config.Labels "com.docker.compose.project.config_files"}}',running[0]]).stdout
            self.restore_mq='compose-mq.yaml' in files
        self.run(self.base+['stop','--timeout','10','worker'])
        self.a40(fixture)
        self.a41('AFTER_BUSINESS_BEFORE_INBOX')
        self.a41('AFTER_INBOX_BEFORE_ACK')
        self.a42(fixture)
        self.report['status']='PASS'

    def cleanup(self):
        for name in list(self.containers):self.remove(name)
        if self.worker_count:self.run((self.mq if self.restore_mq else self.base)+['up','-d','--no-build','--no-deps','--scale','worker='+str(self.worker_count),'worker'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',default='chargehub');parser.add_argument('--api',default='http://127.0.0.1:8080')
    parser.add_argument('--gateway',default='http://127.0.0.1:18081');parser.add_argument('--device',default='http://127.0.0.1:18082')
    parser.add_argument('--timeout',type=int,default=180);parser.add_argument('--report',type=Path,default=ROOT/'.local/reports/mq.json')
    args=parser.parse_args();os.chdir(ROOT);driver=FaultDriver(args);failure=None
    try:driver.execute()
    except Exception as error:failure=error;driver.report.update(status='FAIL',failureType=type(error).__name__,failure=str(error))
    finally:
        try:driver.cleanup()
        except Exception as error:failure=failure or error;driver.report.update(status='FAIL',cleanupFailure=type(error).__name__)
        driver.report['finishedAt']=datetime.now(timezone.utc).isoformat();args.report.parent.mkdir(parents=True,exist_ok=True)
        driver.report['nativeSmoke']=json.loads((ROOT/'.local/reports/mq-native-smoke.json').read_text()) if (ROOT/'.local/reports/mq-native-smoke.json').exists() else None
        args.report.write_text(json.dumps(driver.report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':driver.report['status'],'cases':[(row['case'],row['name'],row['status']) for row in driver.report['cases']],'report':str(args.report),'failure':str(failure) if failure else None},ensure_ascii=False))
    return bool(failure)


if __name__=='__main__':raise SystemExit(main())
