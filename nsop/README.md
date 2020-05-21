# nsop
Python (flask) based NSO portal

The nsop is provided as an example of using NSO and JSON RPC to implement
a portal for NSO. It is NOT provided as a product or production ready portal
product. It's intended to provide a quick and dirty way to add GUI support 
for NSO services, functions etc. It is suitable for PoC and demo level
engagements.

1. Prerequisites

   The NSO portal example requires installion of the python flask module
   See (http://flask.pocoo.org/)
  
   The portal itself uses/relies heavily on Bootstrap3 
   See http://getbootstrap.com/javascript/

   For various reasons, I've provided an unmodifed version of bootstrap as 
   part of the portal

2. Starting up the portal

   There are several start options for the NSO portal 

   python nsop.py --help
   usage: nsop.py [-h] [-d] [-p [PORT]] [-s [SERVER]]

   optional arguments:
     -h, --help            show this help message and exit
     -d, --debug           Enable debug mode
     -p [PORT], --port [PORT] Configure NSOP port (default=4000)
     -s [SERVER], --server [SERVER] Configure NSOP host default=localhost

2. Basic startup

   python nsop.py
   
3. Logging into the portal

   http://<portal-address>:4000

   Use the same credentials NSO is utilizing to log in

4. Sample pages provided for NSO L3VPN example

5. Extending the portal 

   TBD
