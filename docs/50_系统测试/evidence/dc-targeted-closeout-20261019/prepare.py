from support import *
for _ in range(60):
 try:
  spec=requests.get('http://127.0.0.1:8087/v3/api-docs',timeout=3).json();break
 except Exception:time.sleep(1)
else:raise RuntimeError('isolated backend unavailable')
save('openapi.json',spec)
sessions={}
for name,pw,kind in [('superadmin','Admin@123456','admin'),('admin1','Admin123456','admin'),('staff1','Staff123456','admin'),('resident1','Resident123456','resident')]:sessions[name]=login(name,pw,kind)
(PRIVATE/'sessions.json').write_text(json.dumps(sessions),'utf-8')
metadata={}
for table in ['resident','sys_user','residence_relation','community','building','unit','house','public_resource','resource_timeslot','housing_timeslot','housing','viewing_appointment','work_order','lease_record','lease_move_out_application','conversation','conversation_participant','notification_channel_log','notice']:
 metadata[table]={'columns':sql('SHOW COLUMNS FROM '+table),'sample':sql('SELECT COUNT(*) FROM '+table)}
save('schema-inventory.json',metadata)
print('ready',len(spec['paths']),'paths','V'+sql('SELECT MAX(version+0) FROM flyway_schema_history WHERE success=1'))
for k in ['resident','residence_relation','community','building','unit','house','public_resource','resource_timeslot','housing_timeslot','housing','viewing_appointment','work_order','lease_record','lease_move_out_application','conversation','conversation_participant','notification_channel_log']:
 print(k,metadata[k]['sample'],metadata[k]['columns'].replace('\n',' | ')[:1150])
print('interfaces')
for path,methods in spec['paths'].items():
 if any(x in path for x in ['/viewing-appointments','/resource-reservations','/channel-levels','/housings/','/residence-applications','/communities/','/leases/']):
  print(path,','.join(methods))
