#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import sys
import argparse
import json
import subprocess
import nso_api
import time

from nso_api import NsoSession as nso

step=1

def step_dispatch(arg):
   ""

class CommitQueue:

     def __init__(self, debug=True):
        self.debug = debug

     def set_session(self, session):
        self.nso = session

     def get_completed(self):
        data = self.nso.show_config('/')
        print data
        #entries = data['result']['data']['tailf-ncs:devices']['commit-queue']['completed']['queue-item']
        #return entries

     def query_completed(self, ids):

        for id in ids:
          path = "/ncs:devices/commit-queue/completed/queue-item{%s}" % id
          query_info = ['id', 'tag', 'when', 'status', 'devices']
          data = self.nso.simple_query("/ncs:devices/commit-queue/completed","queue-item[id=\'%d\']" % id, query_info) 
          #cqi = [dict(zip(query_info, values)) for values in data['result']['results']]
          cq = [dict(zip(query_info, values)) for values in data['result']['results']][0]
          cq['devices'] = ','.join(self.nso.getValue(path + "/devices"))
         
          
          data = self.nso.simple_query(path, "failed", ['name'])
          fdata  = [item for sublist in data['result']['results'] for item in sublist]
          cq['failed-devices'] = ','.join(fdata)
          
          print("\t\tID               Tag          Status Failed Devices Device(s)")
          print("\t\t---------------- ------------ ------ -------------- ----------------")
 
          print("\t\t{0:16} {1:12} {2:6} {3:12} {4:12}".format(cq['id'],
                                                               cq['tag'],
                                                               cq['status'],
                                                               cq['failed-devices'],
                                                               cq['devices']))
          
     def query(self):

        query_info = ['id', 'age', 'status', 'devices','waiting-for', 'is-atomic']
        data = self.nso.simple_query('/ncs:devices/commit-queue','queue-item', query_info)
        cqi = [ dict(zip(query_info, values)) for values in data['result']['results']]
        for cq in cqi:
            s = ','
            res = self.nso.getValue("/devices/commit-queue/queue-item{%s}/devices" % cq['id'])
            s = s.join(res)
            print res
            cq['devices'] = s
        if len(cqi):
           print("Commit Queue Entries:{0}".format(len(cqi)))
        print("\t\tID               Age    Status     Atomic Devices")
        print("\t\t---------------- ------ ---------- ------ ------------")
        for cq in cqi:
            print("\t\t{0:16} {1:6} {2:10} {3:6} {4:12}".format(cq['id'],
                                                                cq['age'],
                                                                cq['status'],
                                                                cq['is-atomic'],
                                                                cq['devices']))
     def get_current(self):
        data = self.nso.show_config('/ncs:devices/commit-queue')

        print data

        #entries = data['result']['data']['tailf-ncs:devices']['commit-queue']['queue-item']
        #return entries
   
def main(argv):

     parser = argparse.ArgumentParser()
     parser.add_argument("-u", "--username", action='store', dest='username', default='admin', help="username")
     parser.add_argument("-p", "--port", action="store", dest='port', nargs='?', type=int, default=8080, help="NSOP port (default=8080)")
     parser.add_argument("-s", "--server", action="store", dest='server', nargs='?', default='127.0.0.1', help="NSOP host default=localhost")
     args = parser.parse_args()
  
     cq = CommitQueue()

     nscc = nso(args.server, None, args.username, 'admin', port=args.port, debug=False)
     nscc.login(args.username, 'admin')
     nscc.getNewTrans('read_write')

     cq.set_session(nscc)
     
     #cq.get_current()
        
     #cq.get_completed()
   
     cq.query_completed([1589932981021])

     #res = nscc.getValue("/devices/commit-queue/queue-item{1589599272082}/devices")
     #print res
     ##
     # Logging out of nso
     ##
     nscc.logout()

if __name__ == '__main__' :
    main(sys.argv[1:])
