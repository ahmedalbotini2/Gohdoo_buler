
// ── بطاقة وضع المحلل ─────────────────────────────────────────────────────────
import 'package:flutter/material.dart';

class ModeCard extends StatelessWidget {
  final bool      selected;
  final bool      enabled;
  final IconData  icon;
  final String    title;
  final String    subtitle;
  final String    label;
  final VoidCallback? onTap;

  const ModeCard({
    required this.selected,
    required this.enabled,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.label,
    required this.onTap,
  });

  static const Color _gold    = Color(0xFFD4AF37);
  static const Color _goldDim = Color(0xFF8A6A00);
  static const Color _surface2 = Color(0xFF1C2118);
  static const Color _textMain = Color(0xFFEDE8D0);
  static const Color _textSub  = Color(0xFF8A8670);

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: enabled ? onTap : null,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 250),
        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
        decoration: BoxDecoration(
          color: selected
              ? _gold.withValues(alpha: 0.08)
              : _surface2,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: selected
                ? _gold.withValues(alpha: 0.5)
                : _goldDim.withValues(alpha: 0.15),
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  icon,
                  size: 18,
                  color: enabled
                      ? (selected ? _gold : _textSub)
                      : _textSub.withValues(alpha: 0.35),
                ),
                const Spacer(),
                // دائرة الاختيار
                AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  width: 16, height: 16,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: selected ? _gold : Colors.transparent,
                    border: Border.all(
                      color: selected
                          ? _gold
                          : _textSub.withValues(alpha: enabled ? 0.4 : 0.15),
                      width: 1.5,
                    ),
                  ),
                  child: selected
                      ? const Icon(Icons.check, size: 10, color: Color(0xFF0C0E0B))
                      : null,
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              title,
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.bold,
                color: enabled
                    ? (selected ? _gold : _textMain)
                    : _textSub.withValues(alpha: 0.35),
              ),
            ),
            const SizedBox(height: 3),
            Text(
              subtitle,
              style: TextStyle(
                fontSize: 11,
                color: enabled
                    ? _textSub
                    : _textSub.withValues(alpha: 0.25),
              ),
            ),
            // مؤشر "معطّل" لوضع API
            if (!enabled) ...[
              const SizedBox(height: 6),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: _textSub.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  'غير متاح',
                  style: TextStyle(
                    fontSize: 9,
                    color: _textSub.withValues(alpha: 0.4),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}