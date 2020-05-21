# The order of packages is significant as there are dependencies between
# the packages. Typically generated namespaces are used by other packages.
PACKAGES =  cisco-iosxr intf hostname base-config

# The create-network argument to ncs-netsim
NETWORK = create-device ./cisco-iosxr-broken r01 create-device packages/cisco-iosxr r02  create-device packages/cisco-iosxr r03 create-device packages/cisco-iosxr r04 create-device packages/cisco-iosxr r05 create-device packages/cisco-iosxr r06 create-device packages/cisco-iosxr r07 create-device ./cisco-iosxr-broken r08

NETSIM_DIR = netsim

all: build-all $(NETSIM_DIR)

build-all:
	for i in $(PACKAGES); do \
		$(MAKE) -C packages/$${i}/src all || exit 1; \
	done

$(NETSIM_DIR):  packages/cisco-iosxr packages/cisco-ios ./cisco-iosxr-broken
	ncs-netsim --dir netsim $(NETWORK)
	ncs-netsim ncs-xml-init > ./ncs-cdb/netsim_devices_init.xml
	cp ./initial_data/iosxr-r01.xml  ./netsim/r01/r01/cdb/
	cp ./initial_data/iosxr-r02.xml  ./netsim/r02/r02/cdb/
	cp ./initial_data/iosxr-r03.xml  ./netsim/r03/r03/cdb/
	cp ./initial_data/iosxr-r04.xml  ./netsim/r04/r04/cdb/
	cp ./initial_data/iosxr-r05.xml  ./netsim/r05/r05/cdb/
	cp ./initial_data/iosxr-r06.xml  ./netsim/r06/r06/cdb/
	cp ./initial_data/iosxr-r07.xml  ./netsim/r07/r07/cdb/
	cp ./initial_data/iosxr-r08.xml  ./netsim/r08/r08/cdb/
        
clean:
	for i in $(PACKAGES); do \
		$(MAKE) -C packages/$${i}/src clean || exit 1; \
	done
	rm -rf netsim running.DB logs/* state/* ncs-cdb/*.cdb *.trace
	rm -rf bin
	rm -rf ncs-cdb/*.xml

start:
	ncs-netsim start
	ncs
	./initial_data/startup.sh

stop:
	-ncs-netsim stop
	-ncs --stop

reset:
	ncs-setup --reset

cli:
	ncs_cli -u admin

release:
	sh ./make_release.sh
