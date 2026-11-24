# Test report

Same test files and commands for every solution (runner/javatest.py).

| solution | public | hidden |
|---|---|---|
| reference | 5 / 5 | 173 / 173 |
| gemini attempt 1 | 5 / 5 | 172 / 173 |
| gemini attempt 2 | 5 / 5 | 173 / 173 |
| gemini attempt 3 | 5 / 5 | 173 / 173 |
| gemini attempt 4 | 5 / 5 | 173 / 173 |
| gemini attempt 5 | 5 / 5 | 172 / 173 |

Reference: run 1 5/5 + 173/173, run 2 5/5 + 173/173, run 3 5/5 + 173/173

Attempts failing at least one hidden test: 2 of 5

Mutants killed by the hidden tests: 8 of 8 (results/mutants.json)

## Failures (actual vs expected)

- **gemini attempt 1**, H151 phantom_committed_before_range_record_still_conflicts (hidden)
  - expected: `OK state={"b"="1"} committed=[2] aborted=[1] incomplete=[] validLength=71 tornTail=false`
  - actual:   `OK state={"b"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=71 tornTail=false`
- **gemini attempt 5**, H151 phantom_committed_before_range_record_still_conflicts (hidden)
  - expected: `OK state={"b"="1"} committed=[2] aborted=[1] incomplete=[] validLength=71 tornTail=false`
  - actual:   `OK state={"b"="1"} committed=[2, 1] aborted=[] incomplete=[] validLength=71 tornTail=false`

## Every test

| suite | test | reference | gemini attempt 1 | gemini attempt 2 | gemini attempt 3 | gemini attempt 4 | gemini attempt 5 |
|---|---|---|---|---|---|---|---|
| public | P01 example1_interleaved_transactions | PASS | PASS | PASS | PASS | PASS | PASS |
| public | P02 example2_torn_tail | PASS | PASS | PASS | PASS | PASS | PASS |
| public | P03 example3_checksum_mismatch_middle | PASS | PASS | PASS | PASS | PASS | PASS |
| public | P04 example4_savepoint_rollback | PASS | PASS | PASS | PASS | PASS | PASS |
| public | P05 example5_checkpoints | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H01 header_empty_log | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H02 header_only_log | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H03 header_partial_magic_one_byte_is_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H04 header_partial_magic_two_bytes_is_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H05 header_three_bytes_wrong_third_is_bad_magic | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H06 header_wrong_magic_last_byte_with_valid_records | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H07 tail_one_leftover_byte_after_commit | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H08 tail_two_leftover_bytes_unknown_type_is_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H09 tail_three_leftover_bytes_unknown_type_is_error | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H10 frame_type_zero_in_middle | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H11 frame_type_eleven_first_record | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H12 tail_payload_cut_short_is_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H13 tail_last_record_bad_checksum_ends_at_L_is_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H14 frame_bad_checksum_one_byte_before_L_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H15 frame_bad_checksum_in_middle | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H16 frame_length_1025_is_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H17 frame_length_1024_is_allowed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H18 frame_length_0x8001_is_too_large_unsigned | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H19 frame_cut_short_length_2000_too_large_beats_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H20 frame_last_record_unknown_type_bad_checksum_is_error | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H21 checksum_over_payload_only_is_rejected | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H22 checksum_txn_ff_bytes_unsigned | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H23 checksum_length_byte_c8_unsigned | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H24 checksum_s1_s2_swapped_is_rejected | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H25 payload_begin_n3_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H26 payload_begin_n5_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H27 payload_put_key_past_payload_end_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H28 payload_put_no_room_for_value_length_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H29 payload_put_one_extra_byte_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H30 payload_delete_n4_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H31 payload_commit_n0_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H32 field_txn_zero_is_bad_txn_id | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H33 field_key_length_zero_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H34 field_key_length_32_with_edge_bytes_is_ok | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H35 field_key_length_33_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H36 field_key_with_space_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H37 field_delete_key_with_0x7f_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H38 field_value_with_space_and_tilde_is_ok | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H39 field_value_with_0x1f_is_bad_value | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H40 field_value_with_0x7f_is_bad_value | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H41 txn_commit_order_not_log_order | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H42 txn_abort_drops_writes_then_empty_commit | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H43 txn_incomplete_sorted_unsigned | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H44 txn_duplicate_open_id | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H45 txn_duplicate_committed_id | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H46 txn_put_after_commit_is_not_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H47 txn_fifth_begin_too_many_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H48 txn_commit_frees_slot | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H49 txn_abort_frees_one_slot_only | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H50 txn_delete_missing_key_in_commit_order | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H51 txn_put_delete_put_empty_value_same_txn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H52 txn_max_id_end_to_end | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H53 prec_phase2_error_early_checksum_error_later | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H54 prec_malformed_early_too_large_later | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H55 prec_bad_txn_id_before_bad_key_same_record | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H56 prec_bad_key_before_txn_not_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H57 prec_duplicate_before_too_many_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H58 prec_commit_in_torn_tail_is_not_applied | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H59 prec_phase2_error_before_torn_tail_is_thrown | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H60 prec_invalid_content_in_torn_record_is_ignored | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H61 prec_first_failing_record_wins_over_check_order | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H62 api_input_not_modified_rich_log | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H63 api_null_input | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H64 header_partial_magic_three_bytes_is_torn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H65 payload_delete_one_extra_byte_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H66 frame_too_large_last_record_bad_checksum_ends_at_L_is_error | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H67 payload_put_value_cut_short_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H68 prec_bad_txn_id_before_too_many_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H69 prec_bad_value_before_txn_not_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H70 l1_exactly_8_writes_then_commit_ok | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H71 l1_ninth_put_same_key_is_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H72 l1_ninth_write_delete_is_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H73 l1_rollback_frees_room_back_to_8_then_commit | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H74 l1_rollback_frees_room_then_ninth_in_list_is_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H75 l1_savepoint_and_rollback_at_8_writes_ok | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H76 prec_bad_value_before_txn_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H77 prec_txn_not_open_before_txn_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H78 sp_payload_n4_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H79 prec_malformed_before_bad_name_savepoint_n6 | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H80 rb_payload_n6_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H81 rb_payload_n4_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H82 sp_name_0x20_is_bad_name | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H83 rb_name_0x7f_is_bad_name_before_no_savepoint | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H84 sp_rb_names_0x21_and_0x7e_ok | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H85 prec_bad_txn_id_before_bad_name | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H86 prec_bad_name_before_txn_not_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H87 sp_txn_not_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H88 prec_txn_not_open_before_no_savepoint | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H89 sp_per_transaction_rollback_in_other_txn_is_no_savepoint | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H90 sp_same_name_two_txns_independent | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H91 sp_reused_name_moves_savepoint | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H92 rb_removes_moved_savepoint_then_no_savepoint | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H93 rb_equal_positions_decided_by_setting_record_offset | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H94 rb_same_savepoint_twice | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H95 rb_to_savepoint_at_position_0 | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H96 rb_commit_applies_kept_writes_in_order | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H97 rb_then_abort_applies_nothing | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H98 incomplete_txn_with_rollback_and_torn_rollback_ignored | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H99 prec_no_savepoint_early_checksum_mismatch_later | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H100 prec_bad_key_before_txn_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H101 rb_without_any_savepoint_is_no_savepoint | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H102 cp_empty_list_first_record_nothing_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H103 cp_ascending_list_differs_from_begin_order | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H104 cp_list_in_begin_order_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H105 cp_unsigned_ascending_order_large_ids | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H106 cp_signed_order_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H107 cp_lists_committed_txn_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H108 cp_leaves_out_open_txn_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H109 cp_repeated_id_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H110 cp_lists_txn_that_begins_later_is_mismatch_not_txn_not_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H111 prec_cp_listed_zero_bad_txn_id_before_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H112 cp_payload_length_zero_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H113 prec_cp_count_not_matching_length_malformed_before_bad_txn_id | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H114 cp_wrong_list_in_torn_tail_is_ignored | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H115 prec_cp_mismatch_early_checksum_mismatch_later | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H116 cp_open_set_excludes_aborted_and_closed_txns | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H117 cp_between_savepoint_and_rollback_changes_nothing | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H118 frame_type_eight_too_large | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H119 cp_ascending_right_count_but_committed_id_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H120 cp_payload_longer_than_count_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H121 cp_four_open_full_list_then_fifth_begin_too_many_open | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H122 prec_cp_255_ids_last_is_zero_bad_txn_id | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H123 cp_empty_list_while_txn_open_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H124 read_own_put_decides_view | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H125 read_uses_snapshot_not_later_commit | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H126 read_expecting_current_state_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H127 read_after_own_delete_is_absent | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H128 read_after_rollback_falls_back_to_snapshot | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H129 read_conflict_turns_commit_into_abort | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H130 write_write_without_reads_never_conflicts | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H131 conflict_aborted_txn_makes_no_conflict | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H132 commit_before_begin_is_not_a_conflict | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H133 rolled_back_write_is_not_a_written_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H134 rollback_does_not_shrink_read_set | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H135 delete_of_missing_key_still_conflicts | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H136 read_flag_two_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H137 read_value_length_not_matching_n_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H138 prec_read_txn_not_open_before_read_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H139 read_value_byte_7f_is_bad_value | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H140 read_key_with_space_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H141 wrong_read_in_torn_tail_is_ignored | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H142 range_counts_snapshot_with_own_writes | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H143 range_ignores_commit_after_begin | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H144 range_counting_current_state_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H145 range_lo_above_hi_counts_zero | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H146 range_lo_above_hi_nonzero_count_is_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H147 range_key_order_prefix_and_inclusive_ends | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H148 phantom_key_inside_range_conflicts | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H149 key_outside_range_does_not_conflict | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H150 empty_range_never_conflicts | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H151 phantom_committed_before_range_record_still_conflicts | PASS | FAIL | PASS | PASS | PASS | FAIL |
| hidden | H152 range_length_not_matching_n_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H153 range_hi_with_space_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H154 range_empty_lo_is_bad_key_not_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H155 prec_range_txn_not_open_before_range_mismatch | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H156 put_then_delete_same_key_view_absent | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H163 conflict_writer_began_before_reader | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H164 read_empty_value_is_present_not_absent | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H165 read_range_at_write_limit_last_write_counts_once | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H166 own_written_key_does_not_conflict_for_reader | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H167 checkpoint_after_conflict_abort_omits_txn | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H168 rollback_does_not_drop_read_range | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H169 read_txn_zero_is_bad_txn_id | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H170 range_hi_of_33_bytes_is_bad_key | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H171 read_absent_with_extra_byte_is_malformed | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H172 prec_read_mismatch_early_checksum_mismatch_later | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H173 range_count_65537_is_not_one | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H157 scale_many_reading_transactions | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H158 scale_large_state_many_snapshots | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H159 scale_long_reader_sees_old_version | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H160 scale_range_counts_over_large_state | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H161 scale_range_snapshot_while_keys_are_added | PASS | PASS | PASS | PASS | PASS | PASS |
| hidden | H162 scale_commit_with_many_read_ranges | PASS | PASS | PASS | PASS | PASS | PASS |
