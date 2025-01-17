import logging
import pprint

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.logger import get_log_file_timestamp
from mobly.controllers import android_device
import time

# Number of seconds for the target to stay discoverable on Bluetooth.
SCAN_TIME = 60

class HelloWorldTest(base_test.BaseTestClass):
    def setup_class(self):
        self.ads = self.register_controller(android_device, min_number=1)
        # The device that is expected to be discovered
        self.target = android_device.get_device(self.ads)
        self.target.debug_tag = 'target'
        self.target.load_snippet('mbs', android_device.MBS_PACKAGE)


    def setup_test(self):
        # Make sure bluetooth is on.
        self.target.mbs.btEnable()
        # Set Bluetooth name on target device.
        self.target.mbs.btSetName('LookForMe!')

    def test_scan(self):
        callbackId = "scanCallback"
        eventId = "onScanResult"
        target_name = self.target.mbs.btGetName()
        begin_time = get_log_file_timestamp()

        self.target.log.info('Scan for %ds with name "%s"',
                             SCAN_TIME, target_name)

        callback = self.target.mbs.bleStartScan(callbackId=callbackId, scanFilters=None, scanSettings=None)
        start_time = time.time()
        
        print(callback.callback_id)
        success = False
        record_list = []
        while time.time() - start_time < SCAN_TIME:
            output = callback.callEventWaitAndGetRpc(callback.callback_id, eventId, 10)
            result = output["data"]["result"]
            print(result)
            if len(result["ScanRecord"]["Services"]):
                print(result["ScanRecord"]["Services"])
            adv_raw = output
            # Get raw advertisement data - GFP
            #parsed_data = get_parsed_adv_data(adv_raw, logger)
            #logger.info(f'Parsed advertising response: {parsed_data}')
            record_list.append(adv_raw)
            # Verify the fast pair initial pairing data is present while in pairing mode
            #verify_fp_pairing_advertisement_data(parsed_data, inPairingMode=True)
            success = True

        with open('record_list.txt', 'w') as f:
            for record in record_list:
                f.write(f"{record}\n")

        time.sleep(5)

        self.target.mbs.bleStopScan(callback.callback_id)

    def teardown_test(self):
        # Turn Bluetooth off on both devices after test finishes.
        self.target.mbs.btDisable()


if __name__ == '__main__':
    test_runner.main()

