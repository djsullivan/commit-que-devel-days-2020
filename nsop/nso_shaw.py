#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import json
import nso_api

class shaw:

    def __init__(self, session):
      self.nso = session
      return

    def getPorts(self, dn):

        path = "/ncs:devices/ncs:device{\"%s\"}" % dn
        params = {'args' : ['interface', 'brief']}
        data = self.nso.solo_action(path + "/live-status/cisco-ios-xr-stats:exec/show", aparams=params)
        
        result = data['result'][0]['value']
        lines = data['result'][0]['value'].split("\r\n")
        
        intfInfo = {}
        ifList = {}
        portInfo = []

        for line in lines[6:]:
          if '' == line:
            break
          ln = line.split()
          intfInfo[ln[0]] = ln

        query_info = ['id','description', 
                      'mtu','ipv4/address/ip', 'ipv4/address/mask', 
                      'ipv6/prefix', 'line-state', 'admin-state']
        
        dat = self.nso.simple_query(path + '/config/cisco-ios-xr:interface', 
                                'TenGigE', 
                                query_info)
        
        data = dat['result']['results']
        ifInfo = [ dict(zip(query_info, values)) for values in data]

        ###
        # Merge configuration and oper data
        ###
        for intf in ifInfo:

          key = intf['id']
          intf['admin-state'] = intfInfo['Te' + key][1]
          intf['line-state'] = intfInfo['Te' + key][2]
          portInfo.append(intf)
        
        return portInfo


    def addOnboard(self, obd):
       
       path = '/cable:cable/cin-network{%s}' % (obd['site'])

       
       if self.nso.exists(path) == False:
           self.nso.create(path)       
       dpn = '/leaves'
       if obd['role'] == 'CCAP':
          dpn = '/ccap'
       if obd['role'] == 'Spine':
           dpn = '/spine'
       
       dpath = path + dpn + '{%s}' % obd['name']

       if self.nso.exists(dpath) == False:
          self.nso.create(path)

       self.nso.set(dpath + '/ip-address', obd['ip-address'])
       self.nso.set(dpath + '/port', obd['port'])

    def getCinNetworks(self):

        query_info = ['site']

        data = self.nso.simple_query('/cable:cable', 'cin-network', query_info)
        cinn = data['result']['results']
        cin = [ dict(zip(query_info, values)) for values in cinn]
        
        for cn in cin:
           path = '/cable:cable/cable:cin-network{\"%s\"}' % cn['site']
           ###
           # Check ccap
           ###
           query_info = ['device', 'ip-address', 'port', 'status']
           data = self.nso.simple_query(path, 'ccap', query_info)
           datas = data['result']['results']
           ccap = [ dict(zip(query_info, values)) for values in datas]
           cn['ccap'] = ccap
           ###
           # Read spine
           ###
           data = self.nso.simple_query(path, 'spine', query_info)
           datas = data['result']['results']
           spine = [ dict(zip(query_info, values)) for values in datas] 
           cn['spine'] = spine
           ###
           # Read leaves
           ###
           data = self.nso.simple_query(path, 'leaves', query_info)
           datas = data['result']['results']
           leaves = [ dict(zip(query_info, values)) for values in datas] 
           cn['leaves'] = leaves
        return cin

    def verifyRpd(self, rpdMacAddress):
        
        action = '/cable:cable/rpd{%s}/verify' % rpdMacAddress

        data = self.nso.solo_action(action)

        status = ''
        message = ''
        result = {}
        results = data['result']
        for d in results:
            if d['name'] == 'status':
                result['status'] = d['value']
            if d['name'] == 'message':
                result['message'] = d['value']
        return result


    def getRpds(self):
        query_info = ['rpd-mac-address', 'state', 'site', 'ccap', 'cin-leaf', 'cin-leaf-interface','cin-leaf-mtu','cin-leaf-ip/ipv4/address',
                      'cin-leaf-ip/ipv4/mask', 'cin-leaf-ip/ipv6/prefix']
        
        data = self.nso.simple_query('/cable:cable', 'rpd', query_info)
        rpds = data['result']['results']
        rpd = [ dict(zip(query_info, values)) for values in rpds]
        
        return rpd 

    def getQos(self):
        query_info = ['device', 'interface', 'policy', 'direction']

        data = self.nso.simple_query('/cable:cable', 'qos', query_info)
        qosd = data['result']['results']
        qos = [ dict(zip(query_info, values)) for values in qosd]

        return qos

    def getAcls(self):
        query_info = ['name', 'ip-type', 'id', 'acl']

        data = self.nso.simple_query('/cable:cable', 'acl', query_info)
        acld = data['result']['results']
        acls = [ dict(zip(query_info, values)) for values in acld]
        
        query_info = ['device', 'interface', 'direction']

        for acl in acls:
            path = '/cable:cable/cable:acl{\"%s\"}' % acl['name']
            data = self.nso.simple_query(path, 'apply', query_info)
            aplyd = data['result']['results']
            apply = [ dict(zip(query_info, values)) for values in aplyd]
            acl['apply'] = apply

        return acls

    def delQoS(self, qos):
    
       path = '/cable:cable/qos{%s %s %s}' % (qos['device'], qos['interface'], qos['policy'])

       if self.nso.exists(path)  == False:
          return False

       self.nso.delete(path)

       return True

    def delAcl(self, acl):
        path = '/cable:cable/acl{%s}' % (acl['name'])
      
        if self.nso.exists(path) == False:
           return False

        self.nso.delete(path)

    def addAcl(self, acl):
  
       path = '/cable:cable/acl{%s}' % (acl['name'])

       if self.nso.exists(path) == False:
           self.nso.create(path)

       self.nso.set(path + '/ip-type', acl['ip-type'])
       self.nso.set(path + '/id', acl['id'])
       self.nso.set(path + '/acl', acl['acl'])

       apath = path + '/apply{%s %s %s}' % (acl['device'], acl['interface'], acl['direction'])
 
       if self.nso.exists(apath) == False:
          self.nso.create(apath)
  

    def addQoS(self, qos):
    
       path = '/cable:cable/qos{%s %s %s}' % (qos['device'], qos['interface'], qos['policy'])

       if self.nso.exists(path)  == False:
          self.nso.create(path)

       self.nso.set(path + '/direction', qos['direction'])

       return True

    def addRpd(self, rpdParams):
    
       path = '/cable:cable/rpd{' + rpdParams['rpd-mac-address'] + '}'

       if self.nso.exists(path)  == False:
          self.nso.create(path)

       self.nso.set(path + '/site', rpdParams['site'])
       self.nso.set(path + '/state', rpdParams['state'])
       self.nso.set(path + '/ccap', rpdParams['ccap'])
       self.nso.set(path + '/cin-leaf', rpdParams['leaf'])
       self.nso.set(path + '/cin-leaf-interface', rpdParams['leaf-intf'])
       self.nso.set(path + '/cin-leaf-ip/ipv4/address', rpdParams['ipv4a'])
       self.nso.set(path + '/cin-leaf-ip/ipv4/mask', rpdParams['ipv4m'])
       self.nso.set(path + '/cin-leaf-ip/ipv6/prefix', rpdParams['ipv6p'])


       return True
   
    def delRpd(self, rpdParams):

       path = '/cable:cable/rpd{' + rpdParams['rpd-mac-address'] + '}'

       if self.nso.exists(path)  == False:
          print "RPD doesn't exist"
          return
       print "Deleting RPD %s" % rpdParams['rpd-mac-address']
       return (self.nso.delete(path))
      

