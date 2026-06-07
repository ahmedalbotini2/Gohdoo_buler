
// ── ويدجت مساعدة للنقاط ────────────────────────────────────────────────────
import 'package:flutter/material.dart';

class TermsBullet extends StatelessWidget {
  final String text;
   TermsBullet({required this.text});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12.0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 4.0),
            child: Icon(Icons.circle, size: 8, color: Color(0xFFD4AF37)),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(text, style: const TextStyle(color: Color(0xFFEDE8D0), height: 1.5, fontSize: 13)),
          ),
        ],
      ),
    );
  }
}

