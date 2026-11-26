"""
Keep asking Oracle Cloud for a free ARM server (VM.Standard.A1.Flex) until one is free.

Hyderabad often answers "Out of host capacity". This retries every ~90 s, tries 2 CPU/12 GB
then 1 CPU/6 GB, and stops the moment one is created. Uses ~/.oci/config (API key).
The result (public IP) is written to deploy/oracle-server.txt.

Run:  python -I deploy/oci_grab.py
"""
import os, sys, time, random, datetime
import oci

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "oracle-server.txt")
NAME = "pocket-agent"
SSH_KEY = open(os.path.expanduser("~/.ssh/oracle_pocket.pub")).read().strip()
SIZES = [(2, 12), (1, 6)]
WAIT = (75, 110)  # seconds between rounds

cfg = oci.config.from_file()
T = cfg["tenancy"]
idn = oci.identity.IdentityClient(cfg)
net = oci.core.VirtualNetworkClient(cfg)
comp = oci.core.ComputeClient(cfg)


def log(*a):
    print(datetime.datetime.now().strftime("%H:%M:%S"), *a, flush=True)


def ensure_network():
    vcns = [v for v in net.list_vcns(T).data if v.lifecycle_state == "AVAILABLE"]
    vcn = vcns[0] if vcns else None
    if not vcn:
        log("creating network")
        vcn = net.create_vcn(oci.core.models.CreateVcnDetails(
            compartment_id=T, cidr_blocks=["10.0.0.0/16"], display_name="pocket-vcn", dns_label="pocket")).data
        vcn = oci.wait_until(net, net.get_vcn(vcn.id), "lifecycle_state", "AVAILABLE").data
    igws = net.list_internet_gateways(T, vcn_id=vcn.id).data
    igw = igws[0] if igws else net.create_internet_gateway(oci.core.models.CreateInternetGatewayDetails(
        compartment_id=T, vcn_id=vcn.id, is_enabled=True, display_name="pocket-igw")).data
    rt = net.get_route_table(vcn.default_route_table_id).data
    if not any(r.network_entity_id == igw.id for r in rt.route_rules):
        net.update_route_table(rt.id, oci.core.models.UpdateRouteTableDetails(route_rules=rt.route_rules + [
            oci.core.models.RouteRule(destination="0.0.0.0/0", destination_type="CIDR_BLOCK", network_entity_id=igw.id)]))
    subs = [s for s in net.list_subnets(T, vcn_id=vcn.id).data
            if s.lifecycle_state == "AVAILABLE" and not s.prohibit_public_ip_on_vnic]
    sub = subs[0] if subs else None
    if not sub:
        log("creating public subnet")
        sub = net.create_subnet(oci.core.models.CreateSubnetDetails(
            compartment_id=T, vcn_id=vcn.id, cidr_block="10.0.0.0/24", display_name="pocket-public",
            dns_label="pub", prohibit_public_ip_on_vnic=False)).data
        sub = oci.wait_until(net, net.get_subnet(sub.id), "lifecycle_state", "AVAILABLE").data
    return sub


def existing():
    for i in comp.list_instances(T).data:
        if i.display_name == NAME and i.lifecycle_state not in ("TERMINATED", "TERMINATING"):
            return i


def public_ip(inst):
    for att in comp.list_vnic_attachments(T, instance_id=inst.id).data:
        v = net.get_vnic(att.vnic_id).data
        if v.public_ip:
            return v.public_ip


def main():
    # a brand-new API key can be refused (401) for a few minutes while it spreads
    for _ in range(60):
        try:
            ad = idn.list_availability_domains(T).data[0].name
            break
        except oci.exceptions.ServiceError as e:
            if e.status != 401:
                raise
            log("key not active yet, waiting")
            time.sleep(20)
    else:
        sys.exit("API key never became active")
    img = comp.list_images(T, operating_system="Canonical Ubuntu", operating_system_version="24.04",
                           shape="VM.Standard.A1.Flex", sort_by="TIMECREATED").data[0]
    sub = ensure_network()
    log("image", img.display_name, "| subnet", sub.display_name, "| AD", ad)
    n = 0
    while True:
        inst = existing()
        if inst:
            break
        n += 1
        for ocpu, mem in SIZES:
            try:
                inst = comp.launch_instance(oci.core.models.LaunchInstanceDetails(
                    compartment_id=T, availability_domain=ad, display_name=NAME,
                    shape="VM.Standard.A1.Flex",
                    shape_config=oci.core.models.LaunchInstanceShapeConfigDetails(ocpus=ocpu, memory_in_gbs=mem),
                    source_details=oci.core.models.InstanceSourceViaImageDetails(image_id=img.id),
                    create_vnic_details=oci.core.models.CreateVnicDetails(subnet_id=sub.id, assign_public_ip=True),
                    metadata={"ssh_authorized_keys": SSH_KEY})).data
                log(f"GOT IT: {ocpu} CPU / {mem} GB")
                break
            except oci.exceptions.ServiceError as e:
                msg = (e.message or "")[:90]
                log(f"try {n} {ocpu}/{mem}: {e.status} {msg}")
                if e.status in (429, 401):
                    time.sleep(30)
                elif "capacity" not in msg.lower() and e.status not in (500, 503):
                    log("stopping: not a capacity problem")
                    sys.exit(1)
        if inst:
            break
        time.sleep(random.randint(*WAIT))
    inst = oci.wait_until(comp, comp.get_instance(inst.id), "lifecycle_state", "RUNNING", max_wait_seconds=900).data
    ip = public_ip(inst)
    open(OUT, "w").write(f"{NAME} RUNNING\nip={ip}\nssh -i ~/.ssh/oracle_pocket ubuntu@{ip}\nid={inst.id}\n")
    log("RUNNING at", ip)


if __name__ == "__main__":
    # every step reuses what already exists, so a full restart after a 401 is safe
    for _ in range(200):
        try:
            main()
            break
        except oci.exceptions.ServiceError as e:
            if e.status not in (401, 429, 500, 503):
                raise
            log(f"Oracle said {e.status} during setup, starting over in 30 s")
            time.sleep(30)
