#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import json
import nso_api


class nsoDashboard:

     def __init__(self, session):
          self.nso = session

     def serviceInfo(self):

         spinfo = self.nso.get_service_points()  
         srvinfo = []
         total = 0
         for sp in spinfo:
           srv = {}  
           srv['path'] = sp
           srv['count'] = self.nso.count(sp)
           srvinfo.append(srv)
           total += srv['count']

         srvinfo.append({'path': 'Total Serivce(s)', 'count': total})
         return srvinfo

     def deviceSummary(self):
         
         deviceInfo = {}

         deviceInfo = {'name' : 'Total', 'count': self.nso.count('/ncs:devices/device')}
         return [deviceInfo]

     def packageInfo(self):
        
        query_info = ['name', 'package-version', 'description', 'oper-status']

        data = self.nso.simple_query('/ncs:packages', 'package', 
                                      query_info)
        ##
        # Convert the resulting list of lists of list of dicts
        ##
        packs = data['result']['results']     
        packages = [ dict(zip(query_info, values)) for values in packs]
        ##
        # For each package entry ascertain the appropriate 
        ##
        for package in packages:
           exists = self.nso.exists(
                  '/ncs:packages/package{' + package['name'] + '}/oper-status/up')
           if exists == True:
              package['oper-status'] = 'Up'
           else:
              package['oper-status'] = 'Down'
        ###
        # Return list of dicts
        ###
        return packages

     def devicesInfo(self):
        
        query_info = ['name', 'address', 'port', 'description', 'authgroup',
                      'device-type', 'device-type/cli/ned-id', 'device-type/generic/ned-id', 
                      'state/oper-state', 'state/admin-state', 
                      'platform/name', 'platform/version', 'platform/model', 'platform/serial-number']

        data = self.nso.simple_query('/ncs:devices', 'device', query_info)
        devs = data['result']['results']
        devices = [ dict(zip(query_info, values)) for values in devs]

        for d in devices:
           if d['device-type/cli/ned-id']:
              s = d['device-type/cli/ned-id'].split(':')
           elif d['device-type/generic/ned-id']:
              s = d['device-type/cli/ned-id'].split(':')
           else:
              s = d['device-type'].split(':')
                      
           d['device-type'] = s[1]
        
        return devices


     def usersInfo(self):
        
        query_info = ['session-id', 'transport', 'username', 'source-host', 'login-time']
        data = self.nso.simple_query('/ncm:netconf-state/sessions', 'session', 
                                      query_info)
    
        users = [ dict(zip(query_info, values)) for values in data['result']['results']]
        for user in users:
             user['transport'] = user['transport'].replace('tncm:', '')
        return users

     def alarmInfo(self):
        
        query_info = ['type', 'device', 'is-cleared', 'last-alarm-text', 'last-status-change']

        data = self.nso.simple_query('/alarms/alarm-list', 'alarm', 
                                      query_info)
        res = data['result']['results']     
        alarms = [ dict(zip(query_info, values)) for values in res]

        for alarm in alarms:
           alarm['type'] = alarm['type'].replace('al:','')
           if alarm['is-cleared'] == 'true':
              alarm['is-cleared'] = 'Cleared'
           else:
              alarm['is-cleared'] = 'Active'
              
        return alarms

     def pythonInfo(self):
   
        path = "/ncs:python-vm/status"
        query_info = ['class-name', 'status']
        dat = self.nso.simple_query(path, 'current/packages/components/class-names', query_info)
        data = dat['result']['results']
        pvms = [ dict(zip(query_info, values)) for values in data]

        pvmi = {}
        pvmi['status'] = 'running'
        pvmi['pvm'] = pvms
              
        for pvm in pvms:
            if not pvm['status'] == 'running':
                 pvmi['status'] == 'failure'
                 break

        return pvmi
     
     def systemInfo(self):
        
        systemInfo = {}
        ###
        # Grab some quick stats for devices, alarms, users, etc.
        ###
        systemInfo['version'] = self.nso.getValue('/tfnm:ncs-state/version')
        systemInfo['jvm-status'] = self.nso.getValue('/ncs:java-vm/status')
        systemInfo['device-count'] = self.nso.count('/ncs:devices/ncs:device') 
        systemInfo['alarms'] = self.nso.count('/al:alarms/al:alarm-list')
        systemInfo['alarms-summary'] = {}
        systemInfo['alarms-summary']['indeterminates'] = int(self.nso.getValue('/al:alarms/summary/indeterminates'))
        systemInfo['alarms-summary']['criticals'] = int(self.nso.getValue('/al:alarms/summary/criticals'))
        systemInfo['alarms-summary']['majors'] =  int(self.nso.getValue('/al:alarms/summary/majors'))
        systemInfo['alarms-summary']['minors'] =  int(self.nso.getValue('/al:alarms/summary/minors'))
        systemInfo['alarms-summary']['warnings'] =  int(self.nso.getValue('/al:alarms/summary/warnings'))
        total = sum(systemInfo['alarms-summary'].values())
        systemInfo['alarms-summary']['total'] = total

        systemInfo['users'] = self.nso.count('/ncm:netconf-state/sessions/session')  
        systemInfo['cpu'] = 23

        return systemInfo
