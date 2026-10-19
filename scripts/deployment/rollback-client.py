#!/usr/bin/env python3
"""Private rollback-stack client; has no host URL override or secret output."""
import argparse
import json
import os
import sys
from pathlib import Path
sys.path.insert(0,'/suite')
from demo import Client,available_connectors,expect,settle,wait_for

p=argparse.ArgumentParser();p.add_argument('mode',choices=['bootstrap','exercise','verify']);args=p.parse_args()
if os.environ.get('CHARGEHUB_ROLLBACK_ISOLATED')!='yes': raise SystemExit('Only isolated rollback topology is permitted')
client=Client('http://api:8080');client.login('customer',os.environ['CHARGEHUB_DEMO_PASSWORD'])
path=Path('/reports/transaction.json')
if args.mode=='verify':
    saved=json.loads(path.read_text());no=saved['orderNo']
    order=client.call('/v1/charge-orders/'+no)
    expect(order['status']=='COMPLETED','Old application cannot read the finalized order')
    bill=client.call('/v1/charge-orders/'+no+'/bill')
    expect(bill['amounts']==saved['amounts'],'Old application changed formal billing facts')
    print(json.dumps({'status':'PASS','orderNo':no,'amounts':bill['amounts'],'scope':'Actual old application login/read of upgraded persistent facts'}))
else:
    _,connector=available_connectors(client)[0]
    choice={'connectorId':connector['connectorId'],'useMembership':False,'couponId':None}
    quote=client.call('/v1/charge-quotes',{**choice,'estimatedEnergyWh':20000})
    order=client.call('/v1/charge-orders',{**choice,'expectedTariffVersion':quote['tariffVersion']})
    if args.mode=='bootstrap':
        client.call('/v1/charge-orders/'+order['orderNo']+'/cancel',{})
        final=wait_for('old-image seed cancellation',lambda:client.call('/v1/charge-orders/'+order['orderNo']),lambda o:o['status']=='CANCELED')
        print(json.dumps({'status':'PASS','orderNo':final['orderNo'],'scope':'Old image created schema and real canceled order'}))
    else:
        gateway=Client('http://gateway:18081',control_key=os.environ['CHARGEHUB_CONTROL_KEY'])
        device=Client('http://device:18082',control_key=os.environ['CHARGEHUB_CONTROL_KEY'])
        result=settle(client,gateway,device,order,2600)
        saved={'orderNo':result['orderNo'],'payNo':result['payNo'],'sessionNo':result['sessionNo'],
               'amounts':result['bill']['amounts'],'funds':result['funds']}
        path.write_text(json.dumps(saved,indent=2)+'\n')
        print(json.dumps({'status':'PASS',**saved}))
