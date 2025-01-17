## Usage

1. Install mobly and clone mobly bundled snippets
  
        pip install mobly
        git clone https://github.com/Thomas-Campion-Bose/mobly-bundled-snippets.git
        Required for Python < 3.11:
            pip uninstall pyreadline
            pip install pyreadline3

2.  Compile and install the bundled snippets

        Requires gradle:
            cd mobly-bundled-snippets
            ./gradlew assembleDebug
            adb install -d -r -g ./build/outputs/apk/debug/mobly-bundled-snippets-debug.apk

        Alternative (easier):
            Download Android Studio (https://developer.android.com/studio)
            Open mobly-bundled-snippets in Android Studio
            Run Module Make (Hammer icon top-right) 
                This will take a minute or two while it downloads necessary gradle/Android SDK verions
                Also there will be a lot of warnings you can ignore
            Install on Android device (Debug icon top-middle)

3.  Use the Mobly snippet shell to interact with the bundled snippets

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

## Debugging Workflow Summary

1. **Run Python Test**: The Python test invokes the `load_snippet` function, which starts the RPC server on the Android device.
2. **Attach ADB Debugger**: Once the server is running, use ADB to attach to the snippet process on the Android device.
3. **Set Breakpoints**: In the Android-side code (within the snippet), set breakpoints where needed to inspect or troubleshoot issues.
4. **Continue Python Execution**: The Python test execution continues normally, unaffected by breakpoints in the Android-side code, unless the test is waiting for a callback event.
5. **Inspect and Debug**: Step through the Android-side code and observe the test execution. Once you’ve identified the issue, you can resolve it and continue testing.

---

## Troubleshooting Tips

- **Check RPC Server Status**: Ensure that the RPC server is up and running by checking Android logs (`adb logcat`) or verifying that the RPC server was successfully initialized.
- **Synchronization Issues**: If you encounter issues where the Python test is not progressing as expected, ensure that the callbacks or asynchronous events on the Android side are being handled correctly. Consider adding additional logging or breakpoints to inspect the event flow.
- **Use Logging**: In addition to breakpoints, leverage Android logging (via `Log.d`, `Log.e`, etc.) to track the flow of snippet execution and pinpoint where issues may arise.
- **Ensure Device is Awake**: A portion of the logic involves coordinating screen interactions, which cannot be done if the screen is off.
- **Ensure Proper Permissions**: Some of the Android APIs require permissions granted by the user or in `Manifest.xml`. Ensure that the device/project is configured correctly.
