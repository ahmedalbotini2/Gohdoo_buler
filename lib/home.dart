import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class SafeScreenHome extends StatefulWidget {
  const SafeScreenHome({super.key});

  @override
  State<SafeScreenHome> createState() => _SafeScreenHomeState();
}

class _SafeScreenHomeState extends State<SafeScreenHome> {
  static const _channel = MethodChannel('com.example.safescreen/monitor');
  bool _isActive = false;

  @override
  void initState() {
    super.initState();
    _checkStatus();
  }

  Future<void> _checkStatus() async {
    try {
      final bool isActive = await _channel.invokeMethod('isMonitoring');
      setState(() {
        _isActive = isActive;
      });
    } on PlatformException catch (e) {
      debugPrint("Failed to get status: '${e.message}'.");
    }
  }

  Future<void> _toggleProtection() async {
    try {
      if (_isActive) {
        await _channel.invokeMethod('stopMonitoring');
      } else {
        await _channel.invokeMethod('startMonitoring');
      }
      await _checkStatus();
    } on PlatformException catch (e) {
      debugPrint("Failed to toggle protection: '${e.message}'.");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SafeScreen'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(
              _isActive ? Icons.security : Icons.gpp_maybe,
              size: 100,
              color: _isActive ? Colors.green : Colors.red,
            ),
            const SizedBox(height: 20),
            Text(
              'Status: ${_isActive ? "Active" : "Inactive"}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 40),
            ElevatedButton.icon(
              onPressed: _toggleProtection,
              icon: Icon(_isActive ? Icons.stop : Icons.play_arrow),
              label: Text(_isActive ? 'Stop Protection' : 'Start Protection'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
                textStyle: const TextStyle(fontSize: 18),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
