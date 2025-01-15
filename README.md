## Usage

1. Install mobly and clone mobly bundled snippets
  
        pip install mobly
        git clone https://github.com/Thomas-Campion-Bose/mobly-bundled-snippets.git

2.  Compile and install the bundled snippets

        cd mobly-bundled-snippets
        ./gradlew assembleDebug
        adb install -d -r -g ./build/outputs/apk/debug/mobly-bundled-snippets-debug.apk

3.  Use the Mobly snippet shell to interact with the bundled snippets

        pip uninstall pyreadline
        pip install pyreadline3
        snippet_shell.py com.google.android.mobly.snippet.bundled
        >>> print(s.help())
        Known methods:
          bluetoothDisable() returns void  // Disable bluetooth with a 30s timeout.
        ...
          wifiDisable() returns void  // Turns off Wi-Fi with a 30s timeout.
          wifiEnable() returns void  // Turns on Wi-Fi with a 30s timeout.
        ...

4.  To use these snippets within Mobly tests, load it on your AndroidDevice objects
    after registering android_device module:
    `python mobly-test.py -c config.yaml`

    ```yaml
    TestBeds:
    - Name: SampleTestBed
      Controllers:
          AndroidDevice: '*'
      TestParams:
          favorite_food: Green eggs and ham.
    ```
    ```python
    import logging
    import pprint

    from mobly import asserts
    from mobly import base_test
    from mobly import test_runner
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
            self.target.log.info('Scan for %ds with name "%s"',
                                SCAN_TIME, target_name)

            callback = self.target.mbs.bleStartScan(callbackId=callbackId, scanFilters=None, scanSettings=None)
            start_time = time.time()
            while time.time() - start_time < SCAN_TIME:
                output = callback.callEventWaitAndGetRpc(callback.callback_id, eventId, 10)
                print(output)

            time.sleep(5)
            
            self.target.mbs.bleStopScan(callback.callback_id)

        def teardown_test(self):
            # Turn Bluetooth off on both devices after test finishes.
            self.target.mbs.btDisable()


    if __name__ == '__main__':
        test_runner.main()
    ```
