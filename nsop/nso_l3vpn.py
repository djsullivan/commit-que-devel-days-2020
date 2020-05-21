#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import json
import nso_api

class l3vpn:

    def __init__(self, session):
      self.nso = session
      return

    def getVpns(self):
        query_info = ['name', 'route-distinguisher', 'qos', 'endpoints']
        
        data = self.nso.simple_query('/l3vpn:vpn', 'l3vpn', query_info)
        l3vpns = data['result']['results']
        vpns = [ dict(zip(query_info, values)) for values in l3vpns]
        
        query_info = ['id', 'ce-device', 'ce-interface', 'ip-network',
                      'bandwidth', 'as-number']
        query_info.append('endpoint')
        
        for vpn in vpns:
          path = '/l3vpn:vpn/l3vpn{\"%s\"}' % (vpn['name'])
          data = self.nso.simple_query(path, 'endpoint', query_info)
          endpointData = data['result']['results']
          endpoint = [ dict(zip(query_info, values)) for values in endpointData]
          vpn['endpoints'] = endpoint
          
        return vpns  

    def getTopology(self):
        query_info = ['name', 'link-vlan','endpoint-1/device', 'endpoint-1/interface', 'endpoint-1/ip-address',
                      'endpoint-2/device', 'endpoint-2/interface', 'endpoint-2/ip-address']

        data = self.nso.simple_query('/l3vpn:topology', 'connection', query_info)
        topologyData = data['result']['results']
        topology = [ dict(zip(query_info, values)) for values in topologyData]
        return topology       

    def addVpn(self, vpnParams):
        
       addType = vpnParams['type']
       ###
       # Add a VPN or a VPN leg
       ###
       if addType == 'vpn':

           vpn = vpnParams['name']
           path = '/l3vpn:vpn/l3vpn{' + vpn + '}'
       
           if self.nso.exists(path) == False :
             result = self.nso.create(path)

           self.nso.set(path + '/route-distinguisher', vpnParams['rd'])
           self.nso.set(path + '/qos', vpnParams['qos'])
                   
       return True
   
    def modifyTopology(self, request):
       try:
         ###
         # Modify existing topology entry
         ###
         connection = request['name']
         path = '/l3vpn:topology/connection{' + connection + '}'
    
         if self.nso.exists(path) == False :
             result = self.nso.create(path)

         self.nso.set(path + '/link-vlan', request['link_vlan'])
         self.nso.set(path + '/endpoint-1/device', request['ep1-device'])
         self.nso.set(path + '/endpoint-1/interface', request['ep1_interface'])
         self.nso.set(path + '/endpoint-1/ip-address', request['ep1_ip'])
         self.nso.set(path + '/endpoint-2/device', request['ep2-device'])
         self.nso.set(path + '/endpoint-2/interface', request['ep2_interface'])
         self.nso.set(path + '/endpoint-2/ip-address', request['ep2_ip'])         
       except:
          return False

       return True

    def addVpnEndpoint(self, vpnParams):
       try:
         ###
         # Add VPN leg
         ###
         vpn = vpnParams['vpn']
         path = '/l3vpn:vpn/l3vpn{' + vpn + '}/endpoint{' + vpnParams['name'] + "}"
       
         if self.nso.exists(path) == False :
             result = self.nso.create(path)

         self.nso.set(path + '/ce', vpnParams['ce-device'])
         self.nso.set(path + '/ce-interface', vpnParams['ce-interface'])
         self.nso.set(path + '/ip-network', vpnParams['ip-network'])
         self.nso.set(path + '/bandwidth', vpnParams['bandwidth'])
         self.nso.set(path + '/as-number', vpnParams['as-number'])
       
       except:
         return False

       return True

    def vpnDel(self, vpnParams):

       path = '/l3vpn:vpn/l3vpn{' + vpnParams['vpn'] + '}'
      
       if vpnParams['type'] == 'endpoint':
          path += '/endpoint{' + vpnParams['endpoint'] + '}'

       print 'verifying path'
       if self.nso.exists(path) == False :
          return('Failed: device does not exists')       

       return (self.nso.delete(path))
      

