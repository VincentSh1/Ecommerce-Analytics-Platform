import unittest
from benchmark import event, expected_items, percentile


class BenchmarkTest(unittest.TestCase):
    def test_repeatable_valid_ids_and_full_refund(self):
        values = [event('fixture', i, '2026-09-08T12:00:00.000Z') for i in range(100)]
        self.assertEqual(values, [event('fixture', i, '2026-09-08T12:00:00.000Z') for i in range(100)])
        self.assertEqual(len({e['eventId'] for e in values}), 100)
        for e in values:
            self.assertEqual(e['eventId'][14], '4')
            self.assertIn(e['eventId'][19], '89ab')
        for i in range(9,100,10):
            for field in ('orderId','amountMinor','quantity','region','productCategory'):
                self.assertEqual(values[i][field], values[i-1][field])

    def test_independent_reducer_accounts_for_all_dimensions(self):
        payment = event('fixture', 8, '2026-09-08T12:00:00.000Z')
        refund = event('fixture', 9, '2026-09-08T12:00:00.000Z')
        items = expected_items([payment, refund], 'test')
        for dimension in ('TOTAL', 'CAT#'+payment['productCategory'], 'REG#'+payment['region']):
            rows = [v for (pk,sk),v in items.items() if pk.startswith('D#test#'+dimension+'#USD#')]
            self.assertEqual(sum(v['grossMinor'] for v in rows), payment['amountMinor'])
            self.assertEqual(sum(v['refundMinor'] for v in rows), payment['amountMinor'])
            self.assertEqual(sum(v['eventCount'] for v in rows), 2)
            self.assertEqual(sum(v['completedCount'] for v in rows), 1)
            self.assertEqual(sum(v['refundCount'] for v in rows), 1)

    def test_nearest_rank_percentiles(self):
        self.assertEqual(percentile(list(range(1,101)), .95), 95)
        self.assertIsNone(percentile([], .5))


if __name__ == '__main__':
    unittest.main()
