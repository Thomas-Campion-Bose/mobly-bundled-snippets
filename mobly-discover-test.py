import logging
import pprint

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.logger import get_log_file_timestamp
from mobly.controllers import android_device
import time

# Number of seconds for the target to stay discoverable on Bluetooth.
SCAN_TIME = 10

class HelloWorldTest(base_test.BaseTestClass):
    def setup_class(self):
        self.ads = self.register_controller(android_device, min_number=1)
        # The device that is expected to be discovered
        self.target = android_device.get_device(self.ads)
        self.target.debug_tag = 'target'
        self.target.load_snippet('mbs', android_device.MBS_PACKAGE)
        time.sleep(10)


    def setup_test(self):
        # Make sure bluetooth is on.
        self.target.mbs.btEnable()
        # Set Bluetooth name on target device.
        self.target.mbs.btSetName('LookForMe!')

    def test_scan(self):
        eventId = "onDiscoveryReceive"
        target_name = self.target.mbs.btGetName()
        begin_time = get_log_file_timestamp()

        self.target.log.info('Scan for %ds with name "%s"',
                             SCAN_TIME, target_name)

        callback = self.target.mbs.boseDiscover()
        start_time = time.time()
        
        print(callback.callback_id)
        device_address = None
        record_list = []
        while time.time() - start_time < SCAN_TIME:
            output = callback.callEventWaitAndGetRpc(callback.callback_id, eventId, 10)["data"]
            print(output["device"]["Name"])
            if (output["device"]["Name"] == "Mathers-C1"):
                device_address = output["device"]["Address"]
                self.target.mbs.boseCancelDiscover(callback.callback_id)
                break
        
        if not device_address:
            self.target.mbs.boseCancelDiscover(callback.callback_id)
            asserts.fail("Device not found")
        
        print(device_address)
        eventId = "onPairingEvent"
        callback = self.target.mbs.bosePairDevice(device_address)
        pairing_success = False
        while time.time() - start_time < SCAN_TIME:
            output = callback.callEventWaitAndGetRpc(callback.callback_id, eventId, 10)["data"]
            if (output["device"]["BondState"] == "BOND_BONDED"):
                pairing_success = True
                break

        asserts.assert_true(pairing_success, "Pairing failed")

        uuid = "76d871a5-adc0-4c61-9cb7-911e2027c929"
        callback = self.target.mbs.boseRfcommConnect(device_address, uuid)
        print("rfcomm connected")
        eventId = "onRfcommDataReceived"
        output = callback.callEventWaitAndGetRpc(callback.callback_id, eventId, 10)["data"]
        print(output)
        self.target.mbs.boseRfcommSend(callback.callback_id, "Helloworld")
        time.sleep(2)
        self.target.mbs.boseRfcommDisconnect(callback.callback_id)
        
        callback = self.target.mbs.boseUnpairDevice(device_address)
        eventId = "onPairingEvent"
        unpairing_success = False
        while time.time() - start_time < SCAN_TIME:
            output = callback.callEventWaitAndGetRpc(callback.callback_id, eventId, 10)["data"]
            if (output["device"]["BondState"] == "BOND_NONE"):
                unpairing_success = True
                break

        asserts.assert_true(unpairing_success, "Unpairing failed")


    def teardown_test(self):
        # Turn Bluetooth off on both devices after test finishes.
        self.target.mbs.btDisable()


if __name__ == '__main__':
    test_runner.main()

