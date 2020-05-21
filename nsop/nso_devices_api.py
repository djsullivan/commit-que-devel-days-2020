#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import json
import nso_api

nedIdMapping = {'netconf' : {'netconf' : 'netconf' },
                'Cisco IOS-XR' : {'type' : 'cli', 'ned-id' : 'cisco-ios-xr-id:cisco-ios-xr'},
                'Cisco IOS' : {'type' : 'cli', 'ned-id' : 'ios-id:cisco-ios'},
                'Cisco Nexus' : {'type' : 'cli', 'ned-id' : 'cisco-nx-id:cisco-nx'},
                'Cisco ASA' : {'type' : 'cli', 'ned-id' : 'asa-id:cisco-asa'},
                'ALU' : {'type' : 'cli', 'ned-id' : 'alu-sr-id:alu-sr'},
                'Arista' : {'type' : 'cli', 'ned-id' : 'arista-dcs-id:arista-dcs'},
               }

class nsoDevices:

    def __init__(self, session):
      self.nso = session
      return

    @staticmethod
    def getNedInfo():
      return nedIdMapping

    def deviceAction(self, act, devices):
       
       dev = devices.split(',')
       res = ''
     
       if (act == 'sync-from') or (act == 'check-sync') or (act == 'sync-to'):
     
          dev = devices.split(',')
          if (dev[0] == 'all'):
            dev.pop(0)
          action = '/ncs:devices/' + act
          results = self.nso.action(action, dev)
          

          for d in results:
             if d['name'] == 'sync-result/device':
               res += "<tr><td>" + d['value'] + "</td>"
             elif d['name'] == 'sync-result/result':
               if (d['value'] == 'true') or (d['value'] == 'in-sync'):
                 res += '<td><span class=\"label label-success\">' + d['value'] + '</span></td><td></td></tr>'
               else:
                 res += '<td><span class=\"label label-danger\">' + d['value'] + '</span></td>'
             elif d['name'] == 'sync-result/info':
                 res += "<td>" + d['value'] + "</td></tr>"

          return res
        
       if (act == 'compare-config'):
            
            for dn in dev:
              results = self.nso.solo_action('/ncs:devices/ncs:device{' + dn + "}/compare-config")

              if 'result' in results:
                if not results['result']:
                  res += '\n*** No Diff detected for device %s ***\n' % dn
                elif results['result'][0]['name'] == 'diff':
                  res += "\n========= %s ==========\n" % dn
                  results['result'][0]['name'] == 'diff'
                  res += results['result'][0]['value']
                      
            return res
      
       return {'errror' : 'command failed to execute'}

    def deviceAdd(self, devParams):

       dn = devParams['name']
       dt = devParams['devtype']
       path = '/ncs:devices/device{' + dn + '}'
       
       if self.nso.exists(path) == False :
          result = self.nso.create(path)
          return('Failed: device already exists')

       self.nso.set(path + '/address', devParams['address'])
       self.nso.set(path + '/port', devParams['port'])
       self.nso.set(path + '/authgroup', devParams['authgroup'])
       self.nso.set(path + '/state/admin-state', devParams['state'])
       
       if (devParams['devtype']  == 'netconf'):
         self.nso.create(path + '/device-type/netconf')
       else:
         self.nso.create(path + '/device-type/'+ nedIdMapping[dt]['type'])
         self.nso.set(path + nedIdMapping[dt]['type'] + '/ned-id', 
                      nedIdMapping[dt]['ned-id'])
                   
       return 'ok'

    def deviceDel(self, devName):
      
       if self.nso.exists(path) == False :
          result = self.nso.create(path)
          return('Failed: device does not exists')       

       self.nso.delete
       return

