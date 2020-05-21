#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import sys
import argparse
import json
import nso_api
from nso_api import NsoSession as nso

def main(argv):

     parser = argparse.ArgumentParser()
     parser.add_argument("-u", "--username", action='store', dest='username', default='admin', help="username")
     parser.add_argument("-p", "--port", action="store", dest='port', nargs='?', type=int, default=8080, help="NSOP port (default=8080)")
     parser.add_argument("-s", "--sserver", action="store", dest='sserver', nargs='?', default='127.0.0.1', help="NSOP host default=localhost")
     parser.add_argument("-d", "--dserver", action="store", dest='dserver', nargs='?', default='127.0.0.1', help="NSOP host default=localhost")
     parser.add_argument("-n", "--name", action="store", dest='name', nargs='?', help="NSO Device name")
     args = parser.parse_args()
  
     print("\tLogging into source NSO")
     snso = nso(args.sserver, None, args.username, 'admin', port=args.port, debug=False)
     snso.login(args.username, 'admin')
     snso.getNewTrans('read_write')

     print("\tLogging into destination NSO")
     dnso = nso(args.dserver, None, args.username, 'admin', port=args.port, debug=False)
     dnso.login(args.username, 'admin')
     dnso.getNewTrans('read_write')

     path = '/ncs:devices/device{"' + args.name + '"}/config'
     data = snso.show_config(path, oper=False, format='json')

     print("\tSource configuation: ")
     sdata = data['result']['data']['tailf-ncs:devices']['device'][0]
     jout = json.dumps(sdata, indent=3, sort_keys=True).replace("\n", "\n              ")
     print("\t\t{0}".format(jout))
     
     print("\tWriting Device Configuration to Destination Device")
     dnso.load(data['result']['data']['tailf-ncs:devices']['device'][0]['config'], path=path, format='json', mode='replace')
     
     print("\tValidating Changes on destination NSO [{0}]".format(args.dserver))
     dnso.validateCommit()

     print("\tCommitting changes on destination NSO [{0}]".format(args.dserver))
     dnso.commit()

     ##
     # Logging out of nso
     ##
     snso.logout()
     dnso.logout()

if __name__ == '__main__' :
    main(sys.argv[1:])
