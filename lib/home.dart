import 'package:flutter/material.dart';
import 'ai_analyzer.dart'; // استيراد المنطق

class SafeScreenHome extends StatefulWidget {
  const SafeScreenHome({super.key});

  @override
  State<SafeScreenHome> createState() => _SafeScreenHomeState();
}

class _SafeScreenHomeState extends State<SafeScreenHome> {
  final SafeScreenController _controller = SafeScreenController();
  bool _isActive = false;

  @override
  void initState() {
    super.initState();
    _initEngine();
  }

  Future<void> _initEngine() async {
    await _controller.initialize(); // تحميل النموذج والربط
    _refreshStatus();
  }

  Future<void> _refreshStatus() async {
    bool status = await _controller.checkMonitoringStatus();
    setState(() => _isActive = status);
  }

  Future<void> _toggleProtection() async {
    if (_isActive) {
      await _controller.stopMonitoring();
    } else {
      await _controller.startMonitoring();
    }
    _refreshStatus();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Ghadhoo - غدو')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              _isActive ? Icons.security : Icons.gpp_maybe,
              size: 100,
              color: _isActive ? Colors.green : Colors.red,
            ),
            const SizedBox(height: 20),
            Text('الحالة: ${_isActive ? "نشط" : "متوقف"}', style: const TextStyle(fontSize: 22)),
            const SizedBox(height: 40),
            ElevatedButton.icon(
              onPressed: _toggleProtection,
              icon: Icon(_isActive ? Icons.stop : Icons.play_arrow),
              label: Text(_isActive ? 'إيقاف الحماية' : 'تفعيل الحماية'),
              style: ElevatedButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16)),
            ),
          ],
        ),
      ),
    );
  }
}